package com.geekmall.modules.auth.vo;

import com.geekmall.modules.user.vo.UserProfileVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 登录成功出参：令牌 + 用户资料。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "登录结果")
public class LoginVO implements Serializable {

    @Schema(description = "JWT 令牌")
    private String token;

    @Schema(description = "令牌类型，请求头写法：Bearer {token}")
    private String tokenType;

    @Schema(description = "有效期（秒）")
    private Long expiresIn;

    @Schema(description = "用户资料")
    private UserProfileVO user;
}
