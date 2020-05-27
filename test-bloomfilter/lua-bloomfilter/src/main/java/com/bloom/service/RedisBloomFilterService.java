package com.bloom.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RedisBloomFilterService {

    @Autowired
    private RedisTemplate redisTemplate;

    public static final String BLOOMFILTER_NAME = "test-bloom-filter2";

    /**
     * 向布隆过滤器添加元素
     * @param str
     * @return
     */
    public Boolean bloomAdd(String str) {
        DefaultRedisScript<Boolean> LuaScript = new DefaultRedisScript<Boolean>();
        LuaScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("bf_add.lua")));
        LuaScript.setResultType(Boolean.class);
        //封装传递脚本参数
        List<String> params = new ArrayList<String>();
        params.add(BLOOMFILTER_NAME);
        params.add(str);
        return (Boolean) redisTemplate.execute(LuaScript, params);
    }

    /**
     * 检验元素是否可能存在于布隆过滤器中 * @param id * @return
     */
    public Boolean bloomExist(String str) {
        DefaultRedisScript<Boolean> LuaScript = new DefaultRedisScript<Boolean>();
        LuaScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("bf_exist.lua")));
        LuaScript.setResultType(Boolean.class);
        //封装传递脚本参数
        ArrayList<String> params = new ArrayList<String>();
        params.add(BLOOMFILTER_NAME);
        params.add(String.valueOf(str));
        return (Boolean) redisTemplate.execute(LuaScript, params);
    }
}