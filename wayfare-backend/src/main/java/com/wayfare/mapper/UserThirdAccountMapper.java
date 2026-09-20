package com.wayfare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wayfare.entity.UserThirdAccount;
import org.apache.ibatis.annotations.Mapper;

/**
 * 第三方登录账号Mapper接口
 *
 * <p>本版按 P0-B 要求仅为预留，不写登录对接逻辑。
 */
@Mapper
public interface UserThirdAccountMapper extends BaseMapper<UserThirdAccount> {
}
