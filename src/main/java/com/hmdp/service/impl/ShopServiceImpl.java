package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    /**
     * 单例模式，reids的查询入口
     */
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if(shop==null){
            return Result.fail("店铺不存哎");
        }
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;

        //1 从redis查询商铺
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        //2 judge if exist in redis
        if(StrUtil.isNotBlank(shopJson)){
            //3 if exist

            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //3.1 判断命中是否为空值
        if(shopJson!=null){
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
            if(!isLock){
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
            if(shop==null){
                // 5.1 将null值写入redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_SHOP_TTL, TimeUnit.MINUTES);

                return null;
            }
            //6 if exist, add it to reids
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_NULL_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //7 释放互斥锁
            unlock(lockKey);
        }
        //8 return to front
        return shop;
    }

    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;

        //1 从redis查询商铺
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        //2 judge if exist in redis
        if(StrUtil.isNotBlank(shopJson)){
            //3 if exist

            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //3.1 判断命中是否为空值
        if(shopJson!=null){
            // return a false message
            return null;
        }

        //4 if not exist,go to database to seek
        Shop shop = getById(id);

        //5 if also not exist
        if(shop==null){
            // 5.1 将null值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_SHOP_TTL, TimeUnit.MINUTES);

            return null;
        }
        //6 if exist, add it to reids
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_NULL_TTL, TimeUnit.MINUTES);
        //7 return to front
        return shop;
    }

    /**
     * 🔒
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    /**
     * 🔒
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }



    @Override
    @Transactional
    /**
     *单体项目 更新数据库相对简单
     */
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        //1 update database
        updateById(shop);
        //2 delete redis
        stringRedisTemplate.delete(CACHE_SHOP_KEY+ id);
        return Result.ok();
    }

}
