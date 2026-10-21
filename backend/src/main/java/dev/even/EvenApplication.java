package dev.even;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EvenApplication {

  public static void main(String[] args) {
    SpringApplication.run(EvenApplication.class, args);
  }
}
