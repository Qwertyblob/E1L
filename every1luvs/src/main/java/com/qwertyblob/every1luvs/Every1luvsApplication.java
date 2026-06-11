package com.qwertyblob.every1luvs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class Every1luvsApplication {

	public static void main(String[] args) {
		SpringApplication.run(Every1luvsApplication.class, args);
	}

}
