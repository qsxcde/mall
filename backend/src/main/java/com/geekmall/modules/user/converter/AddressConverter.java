package com.geekmall.modules.user.converter;

import com.geekmall.modules.user.entity.UserAddress;
import com.geekmall.modules.user.vo.AddressVO;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 收货地址对象转换。
 */
public final class AddressConverter {

    private AddressConverter() {
    }

    public static AddressVO toVO(UserAddress entity) {
        if (entity == null) {
            return null;
        }
        AddressVO vo = new AddressVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setPhone(entity.getPhone());
        vo.setProvince(entity.getProvince());
        vo.setCity(entity.getCity());
        vo.setDistrict(entity.getDistrict());
        vo.setDetail(entity.getDetail());
        vo.setIsDefault(entity.getIsDefault());
        vo.setFullAddress(Stream.of(entity.getProvince(), entity.getCity(), entity.getDistrict(), entity.getDetail())
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("")));
        return vo;
    }
}
