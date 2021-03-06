package co.elastic.apm.report;

import co.elastic.apm.impl.error.ErrorCapture;
import co.elastic.apm.impl.payload.ProcessInfo;
import co.elastic.apm.impl.payload.Service;
import co.elastic.apm.impl.payload.SystemInfo;
import co.elastic.apm.impl.transaction.Transaction;
import co.elastic.apm.objectpool.Recyclable;
import co.elastic.apm.util.ExecutorUtils;
import co.elastic.apm.util.MathUtils;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventTranslator;
import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.dsl.Disruptor;

import javax.annotation.Nullable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static co.elastic.apm.report.ApmServerReporter.ReportingEvent.ReportingEventType.ERROR;
import static co.elastic.apm.report.ApmServerReporter.ReportingEvent.ReportingEventType.FLUSH;
import static co.elastic.apm.report.ApmServerReporter.ReportingEvent.ReportingEventType.TRANSACTION;

/**
 * This reporter asynchronously reports {@link Transaction}s to the APM server
 * <p>
 * It uses a Disruptor/ring buffer to decouple the {@link Transaction} producing threads from the thread that actually sends the payload
 * </p>
 */
public class ApmServerReporter implements Reporter {

    private static final EventTranslatorOneArg<ReportingEvent, Transaction> TRANSACTION_EVENT_TRANSLATOR = new EventTranslatorOneArg<ReportingEvent, Transaction>() {
        @Override
        public void translateTo(ReportingEvent event, long sequence, Transaction t) {
            event.transaction = t;
            event.type = TRANSACTION;
        }
    };
    private static final EventTranslator<ReportingEvent> FLUSH_EVENT_TRANSLATOR = new EventTranslator<ReportingEvent>() {
        @Override
        public void translateTo(ReportingEvent event, long sequence) {
            event.type = FLUSH;
        }
    };
    private static final EventTranslatorOneArg<ReportingEvent, ErrorCapture> ERROR_EVENT_TRANSLATOR = new EventTranslatorOneArg<ReportingEvent, ErrorCapture>() {
        @Override
        public void translateTo(ReportingEvent event, long sequence, ErrorCapture error) {
            event.error = error;
            event.type = ERROR;
        }
    };

    private final Disruptor<ReportingEvent> disruptor;
    private final AtomicInteger dropped = new AtomicInteger();
    private final boolean dropTransactionIfQueueFull;
    private final ReportingEventHandler reportingEventHandler;
    @Nullable
    private ScheduledThreadPoolExecutor flushScheduler;

    public ApmServerReporter(Service service, ProcessInfo process, SystemInfo system, PayloadSender payloadSender,
                             boolean dropTransactionIfQueueFull, ReporterConfiguration reporterConfiguration) {
        this.dropTransactionIfQueueFull = dropTransactionIfQueueFull;
        disruptor = new Disruptor<>(new TransactionEventFactory(), MathUtils.getNextPowerOf2(reporterConfiguration.getMaxQueueSize()), new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setDaemon(true);
                thread.setName("apm-reporter");
                return thread;
            }
        });
        reportingEventHandler = new ReportingEventHandler(service, process, system, payloadSender, reporterConfiguration);
        disruptor.handleEventsWith(reportingEventHandler);
        disruptor.start();
        if (reporterConfiguration.getFlushInterval() > 0) {
            flushScheduler = ExecutorUtils.createSingleThreadSchedulingDeamonPool("elastic-apm-transaction-flusher", 1);
                flushScheduler.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    disruptor.publishEvent(FLUSH_EVENT_TRANSLATOR);
                }
            }, reporterConfiguration.getFlushInterval(), reporterConfiguration.getFlushInterval(), TimeUnit.SECONDS);
        }
    }

    @Override
    public void report(Transaction transaction) {
        if (!tryAddEventToRingBuffer(transaction, TRANSACTION_EVENT_TRANSLATOR)) {
            transaction.recycle();
        }
    }

    @Override
    public int getDropped() {
        return dropped.get();
    }

    /**
     * Flushes pending {@link ErrorCapture}s and {@link Transaction}s to the APM server.
     * <p>
     * This method may block for a while until a slot in the ring buffer becomes available.
     * </p>
     *
     * @return A {@link Future} which resolves when the flush has been executed.
     */
    @Override
    public Future<Void> flush() {
        disruptor.publishEvent(FLUSH_EVENT_TRANSLATOR);
        final long cursor = disruptor.getCursor();
        return new Future<Void>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public boolean isDone() {
                return false;
            }

            @Override
            public Void get() throws InterruptedException, ExecutionException {
                while (!isEventProcessed(cursor)) {
                    Thread.sleep(1);
                }
                return null;
            }

            /*
             * This might not a very elegant or efficient implementation but it is only intended to be used in tests anyway
             */
            @Override
            public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                for (; timeout > 0 && !isEventProcessed(cursor); timeout--) {
                    Thread.sleep(1);
                }
                if (!isEventProcessed(cursor)) {
                    throw new TimeoutException();
                }
                return null;
            }
        };
    }

    private boolean isEventProcessed(long sequence) {
        return disruptor.getSequenceValueFor(reportingEventHandler) >= sequence;
    }

    @Override
    public void close() {
        disruptor.shutdown();
        if (flushScheduler != null) {
            flushScheduler.shutdown();
        }
    }

    @Override
    public void report(ErrorCapture error) {
        if (!tryAddEventToRingBuffer(error, ERROR_EVENT_TRANSLATOR)) {
            error.recycle();
        }
    }

    private <E extends Recyclable> boolean tryAddEventToRingBuffer(E event, EventTranslatorOneArg<ReportingEvent, E> eventTranslator) {
        if (dropTransactionIfQueueFull) {
            boolean queueFull = !disruptor.getRingBuffer().tryPublishEvent(eventTranslator, event);
            if (queueFull) {
                dropped.incrementAndGet();
                return false;
            }
        } else {
            disruptor.getRingBuffer().publishEvent(eventTranslator, event);
        }
        return true;
    }

    static class ReportingEvent {
        @Nullable
        Transaction transaction;
        @Nullable
        ReportingEventType type;
        @Nullable
        ErrorCapture error;

        public void setTransaction(Transaction transaction) {
            this.type = ReportingEventType.TRANSACTION;
            this.transaction = transaction;
        }

        public void resetState() {
            this.transaction = null;
            this.type = null;
            this.error = null;
        }

        enum ReportingEventType {
            FLUSH, TRANSACTION, ERROR
        }
    }

    static class TransactionEventFactory implements EventFactory<ReportingEvent> {
        @Override
        public ReportingEvent newInstance() {
            return new ReportingEvent();
        }
    }
}
