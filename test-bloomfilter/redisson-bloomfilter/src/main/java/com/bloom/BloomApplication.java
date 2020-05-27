package com.bloom;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.*;

@SpringBootApplication
public class BloomApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(BloomApplication.class, args);
        RedissonClient redisson = context.getBean(RedissonClient.class);
        RBloomFilter bf = redisson.getBloomFilter("test-filter");
        bf.tryInit(100000L, 0.03);
        Set<String> set = new HashSet<String>(1000);
        List<String> list = new ArrayList<String>(1000);
        //向布隆过滤器中填充数据，为了测试真实，我们记录了 1000 个 uuid，另外 9000个作为干扰数据
        for (int i = 0; i < 10000; i++) {
            String uuid = UUID.randomUUID().toString();
            if (i < 1000) {
                set.add(uuid);
                list.add(uuid);
            }

            bf.add(uuid);
        }

        int wrong = 0; // 布隆过滤器误判的次数
        int right = 0;// 布隆过滤器正确次数
        for (int i = 0; i < 10000; i++) {
            String str = i % 10 == 0 ? list.get(i / 10) : UUID.randomUUID().toString();
            if (bf.contains(str)) {
                if (set.contains(str)) {
                    right++;
                } else {
                    wrong++;
                }
            }
        }

        //right 为1000
        System.out.println("right:" + right);
        //因为误差率为3%，所以一万条数据wrong的值在30左右
        System.out.println("wrong:" + wrong);
        //过滤器剩余空间大小
        System.out.println(bf.count());
    }
}
