package com.mia_princz.mse.execution;

import org.springframework.boot.SpringApplication;

public class TestExecutionServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(ExecutionServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
