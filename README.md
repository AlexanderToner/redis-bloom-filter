# SpringBoot + Redis布隆过滤器拦截无效请求

[toc]

## 简述

关于布隆过滤器的详细介绍，我在这里就不再赘述一遍了，不了解的可以移步[BloomFilter 布隆过滤器-简述](https://blog.csdn.net/qq_37012496/article/details/105992181)

本文重点是在 springboot 中使用 redis 的布隆过滤器

我们首先知道：BloomFilter使用长度为m bit的字节数组，使用k个hash函数，增加一个元素: 通过k次hash将元素映射到字节数组中k个位置中，并设置对应位置的字节为1。查询元素是否存在: 将元素k次hash得到k个位置，如果对应k个位置的bit是1则认为存在，反之则认为不存在。

Guava 中已经有具体的实现，而在我们实际生产环境中，本地的存储往往无法满足我们实际的 需求。这时候就需要我们使用 redis 了。

## Redis 安装 Bloom Filter

```shell
git clone https://github.com/RedisLabsModules/redisbloom.git
cd redisbloom
make # 编译

vi redis.conf
## 增加配置
loadmodule /usr/local/web/redis/RedisBloom-1.1.1/rebloom.so

##redis 重启
#关闭
./redis-cli -h 127.0.0.1 -p 6379 shutdown
#启动
./redis-server ../redis.conf &
```

![image-20200526194657530](/Users/toner/Library/Application Support/typora-user-images/image-20200526194657530.png)

### 基本指令

```shell
#创建布隆过滤器，并设置一个期望的错误率和初始大小
bf.reserve userid 0.01 100000
#往过滤器中添加元素
bf.add userid '181920'
#判断指定key的value是否在bloomfilter里存在，存在：返回1，不存在：返回0
bf.exists userid '101310299'
```

## 结合 SpingBoot

搭建一个简单的 springboot 框架

### 方式一

配置

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.bloom</groupId>
    <artifactId>test-bloomfilter</artifactId>
    <version>1.0-SNAPSHOT</version>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>1.5.8.RELEASE</version>
        <relativePath/> <!-- lookup parent from repository -->
    </parent>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-lang3</artifactId>
            <version>3.0.1</version>
        </dependency>
    </dependencies>
</project>
```

redis本身对布隆过滤器就有一个很好地实现，在 java 端，我们直接导入 redisson 的 jar包即可

```xml
<dependency>
  <groupId>org.redisson</groupId>
  <artifactId>redisson</artifactId>
  <version>3.8.2</version>
</dependency>
```

将 Redisson实例 注入 SpringIOC 容器中

```java
@Configuration
public class RedissonConfig {

    @Value("${redisson.redis.address}")
    private String address;

    @Value("${redisson.redis.password}")
    private String password;

    @Bean
    public Config redissionConfig() {
        Config config = new Config();
        SingleServerConfig singleServerConfig = config.useSingleServer();
        singleServerConfig.setAddress(address);
        if (StringUtils.isNotEmpty(password)) {
            singleServerConfig.setPassword(password);
        }

        return config;
    }

    @Bean
    public RedissonClient redissonClient() {
        return Redisson.create(redissionConfig());
    }
}
```

配置文件

```properties
redisson.redis.address=redis://127.0.0.1:6379
redisson.redis.password=
```

最后测试我们的布隆过滤器

```java
@SpringBootApplication
public class BloomApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(BloomApplication.class, args);
        RedissonClient redisson = context.getBean(RedissonClient.class);
        RBloomFilter bf = redisson.getBloomFilter("test-bloom-filter");
        bf.tryInit(100000L, 0.03);
        Set<String> set = new HashSet<String>(1000);
        List<String> list = new ArrayList<String>(1000);
      //向布隆过滤器中填充数据，为了测试真实，我们记录了 1000 个 uuid，另外 9000个作为干扰数据
        for (int i = 0; i < 10000; i++) {
           String uuid = UUID.randomUUID().toString();
          if(i<1000){
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
```

以上使我们使用 redisson 的使用方式，下面介绍一种比较原始的方式，使用`lua`脚本的方式

### 方式二

**bf_add.lua**

```lua
local bloomName = KEYS[1]
local value = KEYS[2]
local result = redis.call('BF.ADD',bloomName,value)
return result
```

**bf_exist.lua**

```lua
local bloomName = KEYS[1]
local value = KEYS[2]
 
local result = redis.call('BF.EXISTS',bloomName,value)
return result
```

```java
@Service
public class RedisBloomFilterService {

    @Autowired
    private RedisTemplate redisTemplate;

    //我们依旧用刚刚的那个过滤器
    public static final String BLOOMFILTER_NAME = "test-bloom-filter";

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
```

最后我们还是用上面的启动器执行测试代码

```java
@SpringBootApplication
public class BloomApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(BloomApplication.class, args);
        RedisBloomFilterService filterService = context.getBean(RedisBloomFilterService.class);
        Set<String> set = new HashSet<String>(1000);
        List<String> list = new ArrayList<String>(1000);
        //向布隆过滤器中填充数据，为了测试真实，我们记录了 1000 个 uuid，另外 9000个作为干扰数据
        for (int i = 0; i < 10000; i++) {
            String uuid = UUID.randomUUID().toString();
            if (i < 1000) {
                set.add(uuid);
                list.add(uuid);
            }

            filterService.bloomAdd(uuid);
        }

        int wrong = 0; // 布隆过滤器误判的次数
        int right = 0;// 布隆过滤器正确次数
        for (int i = 0; i < 10000; i++) {
            String str = i % 10 == 0 ? list.get(i / 10) : UUID.randomUUID().toString();
            if (filterService.bloomExist(str)) {
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
    }
}
```

相比而言，个人比较推荐第一种，实现的原理都是差不多，redis 官方已经为我封装好了执行脚本，和相关 api，用官方的会更好一点

以上就是我们在分布式中使用布隆过滤器的两种方式，代码放到 git 上自取https://github.com/AlexanderToner/redis-bloom-filter

## 参考
