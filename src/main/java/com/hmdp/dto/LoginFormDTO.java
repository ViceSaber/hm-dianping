package com.hmdp.dto;

import lombok.Data;

//有验证码和密码登录两种
@Data
public class LoginFormDTO {
    private String phone;
    private String code;
    private String password;
}
