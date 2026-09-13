package com.pinhaoche.auth;

import com.pinhaoche.common.auth.TokenStore;
import com.pinhaoche.user.AppUser;
import com.pinhaoche.user.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 登录服务：code → openid → upsert 用户 → 签发 token。
 * 登录本身不幂等（code 一次性、重复登录只换 token），不属于规范 §21 列出的幂等接口。
 */
@Service
public class AuthService {

    private final WeChatGateway weChatGateway;
    private final AppUserRepository userRepository;
    private final TokenStore tokenStore;

    public AuthService(WeChatGateway weChatGateway, AppUserRepository userRepository, TokenStore tokenStore) {
        this.weChatGateway = weChatGateway;
        this.userRepository = userRepository;
        this.tokenStore = tokenStore;
    }

    /** 与 openapi LoginResponse 一致：{ token, user: { id, nickname, avatar } }（RFC-002）。 */
    public record LoginUser(long id, String nickname, String avatar) {
    }

    public record LoginResult(String token, LoginUser user) {
    }

    @Transactional
    public LoginResult login(String code) {
        String openid = weChatGateway.codeToOpenid(code);
        AppUser user = userRepository.findByOpenid(openid).orElseGet(() -> {
            AppUser created = new AppUser(openid, OffsetDateTime.now());
            return userRepository.save(created);
        });
        if (!"ACTIVE".equals(user.getStatus())) {
            throw com.pinhaoche.common.api.ApiException.forbidden("账号已被停用");
        }
        return new LoginResult(tokenStore.issue(user.getId()),
                new LoginUser(user.getId(), user.getNickname(), user.getAvatar()));
    }
}
