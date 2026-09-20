package com.wayfare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wayfare.entity.WorkImage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 作品图片明细Mapper接口
 *
 * <p>多图发布与详情页真实图片列表都依赖它（P0-C 用它替换掉了「把 imageUrls 写死为封面一张」的假实现）。
 */
@Mapper
public interface WorkImageMapper extends BaseMapper<WorkImage> {

    /**
     * 真·批量插入（一条 INSERT 多个 VALUES），而不是在 Service 里循环调 insert。
     *
     * <p>为什么必须自定义 SQL：MyBatis-Plus 的 {@code BaseMapper} 没有批量插入，
     * 而 {@code IService.saveBatch} 在本项目用不上（各 Mapper 是裸 BaseMapper，没有继承 ServiceImpl）；
     * 循环单条插入在 20 张图时要发 20 次往返。
     *
     * <p>注意：自定义 SQL <b>不会触发 {@code MyMetaObjectHandler}</b> 的自动填充，
     * 所以 created_at 由 SQL 里的 NOW() 显式写入 —— 漏掉它会让该列取默认值而非真实插入时间。
     */
    @Insert("<script>" +
            "INSERT INTO work_image (work_id, image_url, width, height, sort, created_at) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.workId}, #{item.imageUrl}, #{item.width}, #{item.height}, #{item.sort}, NOW())" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<WorkImage> imageList);
}
