package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@SuppressWarnings("all")
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    /**
     * 单例模式，reids的查询入口
     */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 线程池，用于缓存重构
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        // Shop shop = queryWithPassThrough(id);
        // 层层封装
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExp(id);

        if (shop == null) {
            return Result.fail("店铺不存哎");
        }
        return Result.ok(shop);
    }


    public Shop queryWithLogicalExp(Long id) {
        String key = CACHE_SHOP_KEY + id;

        //1 从redis查询商铺
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        //2 judge if exist in redis
        if (StrUtil.isBlank(shopJson)) {
            //3 if exist

            return null;
        }
        //4 命中需要判断过期时间，把json反序列化
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        //由于泛型报异常，所以用JO
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1 未过期，直接返回
            return shop;
        }
        //5.2 已过期，需要缓存重建
        //6 缓存重建
        //7 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //8 判断是否获取锁成功
        if (isLock) {
            //8.1 成功，(开启独立线程)，实现缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 8.2缓存重建
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 8.3释放锁
                    unlock(lockKey);
                }
            });
        }
        //8.2 失败，返回店铺信息（过期
        return shop;
    }

    /**
     * 缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;

        //1 从redis查询商铺
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        //2 judge if exist in redis
        if (StrUtil.isNotBlank(shopJson)) {
            //3 if exist

            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //3.1 判断命中是否为空值
        if (shopJson != null) {
            // return a false message
            return null;
        }

        //4 实现缓存重建
        //4.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2 判断是否成功
            if (!isLock) {
                //4.3 失败->休眠并重试
                Thread.sleep(50);
                queryWithMutex(id);
            }
            //4.4，成功-根据id查询数据库
            //4 if not exist,go to database to seek
            shop = getById(id);
            // 模拟重建的延时
            Thread.sleep(200);
            //5 if also not exist
            if (shop == null) {
                // 5.1 将null值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_SHOP_TTL, TimeUnit.MINUTES);

                return null;
            }
            //6 if exist, add it to reids
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_NULL_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7 释放互斥锁
            unlock(lockKey);
        }
        //8 return to front
        return shop;
    }

    /**
     * 缓存穿透
     * @param id
     * @return
     */
   /* public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;

        //1 从redis查询商铺
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        //2 judge if exist in redis
        if (StrUtil.isNotBlank(shopJson)) {
            //3 if exist

            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //3.1 判断命中是否为空值
        if (shopJson != null) {
            // return a false message
            return null;
        }

        //4 if not exist,go to database to seek
        Shop shop = getById(id);

        //5 if also not exist
        if (shop == null) {
            // 5.1 将null值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_SHOP_TTL, TimeUnit.MINUTES);

            return null;
        }
        //6 if exist, add it to reids
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_NULL_TTL, TimeUnit.MINUTES);
        //7 return to front
        return shop;
    }
*/

    /**
     * 🔒
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 🔒
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 把一个店铺添加到redis并设置逻辑过期
     *
     * @param id
     * @param expireSeconds
     */
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //1 查询店铺数据from database
        Shop shop = getById(id);
        Thread.sleep(200);
        //2 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    @Override
    @Transactional
    /**
     *单体项目 更新数据库相对简单
     */
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1 update database
        updateById(shop);
        //2 delete redis
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

}
