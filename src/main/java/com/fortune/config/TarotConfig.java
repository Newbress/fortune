package com.fortune.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fortune.tarot.CardDeckLoader;
import com.fortune.tarot.TarotCard;
import com.fortune.tarot.TarotDealer;
import com.fortune.tarot.TarotProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.util.List;

@Configuration
@EnableConfigurationProperties(TarotProperties.class)
public class TarotConfig {

    /** 카드 데이터가 잘못되면 여기서 예외가 나서 기동이 실패한다. */
    @Bean
    public List<TarotCard> tarotDeck(ObjectMapper mapper) {
        return CardDeckLoader.loadFromClasspath(mapper);
    }

    @Bean
    public TarotDealer tarotDealer(List<TarotCard> tarotDeck, TarotProperties props) {
        return new TarotDealer(tarotDeck, new SecureRandom(), props.reversedProbability());
    }
}
