package com.hmall.cart.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.api.client.ItemClient;
import com.hmall.api.dto.ItemDTO;
import com.hmall.cart.domain.dto.CartFormDTO;
import com.hmall.cart.domain.po.Cart;
import com.hmall.cart.domain.vo.CartVO;
import com.hmall.cart.mapper.CartMapper;
import com.hmall.cart.service.ICartService;
import com.hmall.common.exception.BizIllegalException;
import com.hmall.common.utils.BeanUtils;
import com.hmall.common.utils.CollUtils;
import com.hmall.common.utils.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;


@Service
public class CartServiceImpl extends ServiceImpl<CartMapper, Cart> implements ICartService {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ItemClient itemClient;

    @Autowired
    DiscoveryClient discoveryClient;
    @Override
    public void addItem2Cart(CartFormDTO cartFormDTO) {
        //1.查询用户的id，来获取购物车信息
        long userId = UserContext.getUser();
        //2.判断是否存在
        if(checkItemExists(cartFormDTO.getItemId(), userId)){
            //2.1存在，则更新数量
            baseMapper.updateNum(cartFormDTO.getItemId(), userId);
            return;
        }
        //2.2 不存在，判断1是否超过购物车数量
        checkCartsFull(userId);
        //3.新增购物车条目
        //3.1转换po
        Cart cart = BeanUtils.copyBean(cartFormDTO, Cart.class);
        //3.2 保存当前用户
        cart.setUserId(userId);
        //3.3保存到数据库
        save(cart);
    }

    @Override
    public List<CartVO> queryMyCarts() {
        // 1.查询我的购物车列表
        UserContext.setUser(1L);
        List<Cart> carts = lambdaQuery().eq(Cart::getUserId, UserContext.getUser()).list();
        if (CollUtils.isEmpty(carts)) {
            return CollUtils.emptyList();
        }

        // 2.转换VO
        List<CartVO> vos = BeanUtils.copyList(carts, CartVO.class);

        // 3.处理VO中的商品信息
        handleCartItems(vos);

        // 4.返回
        return vos;
    }

    private void handleCartItems(List<CartVO> vos) {
        // TODO 1.获取商品id
        Set<Long> itemIds = vos.stream().map(CartVO::getItemId).collect(Collectors.toSet());

        //查询服务
//        List<ServiceInstance> instances = discoveryClient.getInstances("item-service");
//        //负载均衡 挑选一个实例
//        ServiceInstance serviceInstance =  instances.get(RandomUtil.randomInt(instances.size()));
//        // 2.查询商品
//        // 2.1.利用RestTemplate发起http请求，得到http的响应
//        ResponseEntity<List<ItemDTO>> response = restTemplate.exchange(
//                serviceInstance.getUri() + "/items?ids={ids}",
//                HttpMethod.GET,
//                null,
//                new ParameterizedTypeReference<List<ItemDTO>>() {},
//                CollUtil.join(itemIds,",")
//        );

        List<ItemDTO> items = itemClient.queryItemByIds(itemIds);

        // 2.2.解析响应
        if (CollUtils.isEmpty(items)) {
            return;
        }
        // 3.转为 id 到 item的map
        Map<Long, ItemDTO> itemMap = items.stream().collect(Collectors.toMap(ItemDTO::getId, Function.identity()));
        // 4.写入vo
        for (CartVO v : vos) {
            ItemDTO item = itemMap.get(v.getItemId());
            if (item == null) {
                continue;
            }
            v.setNewPrice(item.getPrice());
            v.setStatus(item.getStatus());
            v.setStock(item.getStock());
        }
    }

    @Override
    public void removeByItemIds(Collection<Long> itemIds) {
        // 1.构建删除条件，userId和itemId
        QueryWrapper<Cart> queryWrapper = new QueryWrapper<Cart>();
        queryWrapper.lambda()
                .eq(Cart::getUserId, UserContext.getUser())
                .in(Cart::getItemId, itemIds);
        // 2.删除
        remove(queryWrapper);
    }

    private void checkCartsFull(Long userId) {
        int count = lambdaQuery().eq(Cart::getUserId, userId).count();
        if (count >= 10) {
            throw new BizIllegalException(StrUtil.format("用户购物车课程不能超过{}", 10));
        }
    }

    private boolean checkItemExists(Long itemId, Long userId) {
        int count = lambdaQuery()
                .eq(Cart::getUserId, userId)
                .eq(Cart::getItemId, itemId)
                .count();
        return count > 0;
    }


}
