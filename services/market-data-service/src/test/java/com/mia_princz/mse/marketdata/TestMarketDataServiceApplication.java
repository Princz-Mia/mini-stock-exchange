package com.mia_princz.mse.marketdata;

import org.springframework.boot.SpringApplication;

public class TestMarketDataServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(MarketDataServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
