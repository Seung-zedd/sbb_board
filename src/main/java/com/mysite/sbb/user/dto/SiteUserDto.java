package com.mysite.sbb.user.dto;

import com.mysite.sbb.user.SiteUser;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SiteUserDto {
    private String username;

    @Builder
    private SiteUserDto(String username) {
        this.username = username;
    }

    public static SiteUserDto from(SiteUser siteUser) {
        if (siteUser == null) { //  null 체크 추가
            return null;
        }
        return SiteUserDto.builder()
                .username(siteUser.getUsername())
                .build();
    }
}
