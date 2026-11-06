package com.fortune.tarot;

import java.util.Optional;

/** 리딩 저장소. 지금은 인메모리 구현만 있고, 다음 단계에서 Redis/DB 구현으로 바꾼다. */
public interface ReadingRepository {

    /** 같은 token 이 있으면 덮어쓴다. 보관 기간은 {@link Reading#createdAt()} 기준이다. */
    void save(Reading reading);

    /** 없거나 보관 기간이 지났으면 비어 있다. */
    Optional<Reading> find(String token);
}
