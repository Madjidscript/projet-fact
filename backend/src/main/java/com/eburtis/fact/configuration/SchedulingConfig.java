package com.eburtis.fact.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Les tâches planifiées : production mensuelle des échéances de loyer. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
