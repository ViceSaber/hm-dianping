package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IShopTypeService typeService;
    @Override
    public Result queryTypeList() {

        //1 从redis查询商铺
        String typeJson = stringRedisTemplate.opsForValue().get(SHOP_TYPE_KEY);

        //2 judge if exist in redis
        if(StrUtil.isNotBlank(typeJson)){
            //3 if exist
            List<ShopType> typeList = JSONUtil.toList(typeJson, ShopType.class);
            return Result.ok(typeList);
        }
        //4 if not exist,go to database to seek
        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();

        //5 if also not exist
        if(typeList==null){
            return Result.fail("店铺倒闭了");
        }

        //6 if exist, add it to reids
        stringRedisTemplate.opsForValue().set(SHOP_TYPE_KEY,JSONUtil.toJsonStr(typeList),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7 return to front
        return Result.ok(typeList);
    }
}
