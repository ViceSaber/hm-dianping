package com.hmdp.utils;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
//定义泛型
@Data
@NoArgsConstructor
public class RedisData<T> {
    private LocalDateTime expireTime;
    private Object data;
}
