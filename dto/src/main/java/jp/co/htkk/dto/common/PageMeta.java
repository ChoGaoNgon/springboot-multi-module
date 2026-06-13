package jp.co.htkk.dto.common;

import com.github.pagehelper.Page;
import lombok.Data;

@Data
public class PageMeta {
    private long total = 0;
    private int pageNum = 0;
    private int pageSize = 0;
    private int pages = 0;

    public static PageMeta of(Page<?> page) {
        PageMeta meta = new PageMeta();
        meta.setTotal(page.getTotal());
        meta.setPageNum(page.getPageNum());
        meta.setPageSize(page.getPageSize());
        meta.setPages(page.getPages());
        return meta;
    }
}
