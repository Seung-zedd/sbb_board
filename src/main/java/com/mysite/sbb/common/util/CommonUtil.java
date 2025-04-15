package com.mysite.sbb.common.util;

import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class CommonUtil {
    // 마크다운 텍스트를 HTML 문서로 변환하여 리턴
    public String markdown(String markdown) {
        Parser parser = Parser.builder()
                .extensions(Arrays.asList(
                        TablesExtension.create(), // 표 지원
                        AutolinkExtension.create() // 자동 링크 변환 지원
                ))
                .build();
        Node document = parser.parse(markdown);
        HtmlRenderer renderer = HtmlRenderer.builder().build();
        return renderer.render(document);
    }
}
