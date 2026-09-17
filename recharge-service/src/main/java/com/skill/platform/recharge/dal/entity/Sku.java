package com.skill.platform.recharge.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 点数套餐（阶梯赠送：100 点 / 550 点（含赠 50）/ 1200 点（含赠 200））。
 */
@Data
@TableName("sku")
public class Sku {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String skuId;

    private String name;

    /** 购买点数（含赠送） */
    private Integer points;

    /** 价格（分） */
    private Long priceFen;

    /** 1=上架 0=下架 */
    private Integer active;

    private Integer sort;

    private LocalDateTime createdAt;
}
