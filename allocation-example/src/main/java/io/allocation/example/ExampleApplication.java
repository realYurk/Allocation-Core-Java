package io.allocation.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A host application that happens to use the allocation engine.
 *
 * <p>Note where this class is: {@code io.allocation.example}. The starter lives in
 * {@code io.allocation.spring}, which this scan does not cover, so every bean the application uses arrived
 * through {@code AutoConfiguration.imports}. That is the whole point of the example — it is the same
 * situation as dropping the component into a backend whose base package is {@code com.ruoyi}, and if the
 * starter ever depended on being component-scanned, this application would fail to start.
 *
 * <p>Run it with {@code mvn -pl allocation-example spring-boot:run}. The sample rule, the sample data and
 * the frozen expected result are all under {@code data/}, and {@code application.yml} configures nothing
 * but two directories.
 */
@SpringBootApplication
public class ExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExampleApplication.class, args);
    }
}
