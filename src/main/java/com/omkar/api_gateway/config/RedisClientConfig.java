package com.omkar.api_gateway.config;

import java.time.Duration;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisClientConfig {

	@Bean
	LettuceClientConfigurationBuilderCustomizer redisConnectTimeout() {
		SocketOptions socketOptions = SocketOptions.builder()
				.connectTimeout(Duration.ofSeconds(1))
				.build();
		ClientOptions clientOptions = ClientOptions.builder()
				.socketOptions(socketOptions)
				.build();
		return builder -> builder.clientOptions(clientOptions);
	}

}
