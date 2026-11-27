package com.neighborroute.telegrambot.config;

import com.neighborroute.telegrambot.bot.NeighborRouteBot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Configuration
public class TelegramConfig {

    @Bean(destroyMethod = "close")
    public TelegramBotsLongPollingApplication telegramApplication(
            @Value("${telegram.bot.token}") String token,
            NeighborRouteBot bot
    ) throws TelegramApiException {

        TelegramBotsLongPollingApplication application =
                new TelegramBotsLongPollingApplication();

        application.registerBot(token, bot);

        return application;
    }
}
