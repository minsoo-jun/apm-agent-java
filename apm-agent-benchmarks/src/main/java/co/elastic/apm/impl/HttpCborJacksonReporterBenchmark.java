package co.elastic.apm.impl;

import co.elastic.apm.CpuProfiler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

public class HttpCborJacksonReporterBenchmark extends AbstractHttpJacksonReporterBenchmark {

    /**
     * Convenience benchmark run method
     * <p>
     * For more accurate results, execute <code>mvn clean package</code> and run the benchmark via
     * <code>java -jar apm-agent-benchmarks/target/benchmarks.jar -prof gc</code>
     */
    public static void main(String[] args) throws RunnerException {
        new Runner(new OptionsBuilder()
            .include(HttpCborJacksonReporterBenchmark.class.getSimpleName())
            .addProfiler(GCProfiler.class)
            .addProfiler(CpuProfiler.class)
            .build())
            .run();
    }

    protected ObjectMapper getObjectMapper() {
        return new ObjectMapper(new CBORFactory());
    }
}
