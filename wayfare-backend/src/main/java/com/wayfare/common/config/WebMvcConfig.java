package com.wayfare.common.config;

import com.wayfare.security.JwtInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册鉴权拦截器与上传目录静态映射。
 *
 * <p><b>「哪些接口允许匿名」的权威定义在 {@link JwtInterceptor} 的 PUBLIC_PATHS 常量里</b>，
 * 本类只做全局注册（{@code /**}）。这一点是踩过坑之后改的，值得记下来：
 *
 * <p>最初把公开路径写进这里的 {@code excludePathPatterns}，结果是
 * <b>被排除的路径完全不进拦截器，UserContext 永远不会被填充</b> ——
 * 已登录用户访问公开路径时后端认不出身份。最典型的受害接口是
 * {@code PUT/DELETE /works/{id}}：为了让未登录用户能看作品详情，{@code /works/{id:[0-9]+}}
 * 必须公开，而白名单按路径匹配、不区分 HTTP 方法，于是登录用户改自己的作品反而被判定「未登录」。
 *
 * <p>现在拆成两步：拦截器先「只要带有效 token 就认身份」（公开路径也不例外），
 * 再「没带 token 且路径不公开才 401」。公开与否的判定用 PathPattern 正则，
 * {@code /works/{id:[0-9]+}} 只放行纯数字 id，避免把 {@code /works/my} 一起放进来。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final FileStorageProperties fileStorageProperties;

    public WebMvcConfig(JwtInterceptor jwtInterceptor, FileStorageProperties fileStorageProperties) {
        this.jwtInterceptor = jwtInterceptor;
        this.fileStorageProperties = fileStorageProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 全局注册，不用 excludePathPatterns —— 公开路径的判定在 JwtInterceptor 内部
        // （见其 PUBLIC_PATHS 与类注释）。若在这里排除，被排除的路径就完全绕过拦截器，
        // 已登录用户在公开路径上也会丢失身份上下文。
        registry.addInterceptor(jwtInterceptor).addPathPatterns("/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 映射上传文件目录，通过 /uploads/** 访问
        String uploadDir = fileStorageProperties.getUploadDir();
        // 确保路径以 / 结尾
        if (!uploadDir.endsWith("/")) {
            uploadDir = uploadDir + "/";
        }
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadDir);
    }
}
