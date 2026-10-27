package com.lazybone.hazmat;

import com.lazybone.hazmat.common.ResourceNotFoundException;
import com.lazybone.hazmat.entity.Chemical;
import com.lazybone.hazmat.repository.ChemicalRepository;
import com.lazybone.hazmat.service.ChemicalService;
import com.lazybone.hazmat.service.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redis 不可用时的降级：缓存开启，但端口指向一个没人监听的地址。
 *
 * 这个测试要证明的不是「缓存生效」，而是**缓存挂掉时服务照常工作**——
 * 档案照常读出来、不存在的 id 照常得到 404、库存视图照常组装。
 * 缓存是一层可以随时摘掉的加速，不是业务依赖；如果 Redis 一挂接口就 500，
 * 那这层缓存就不该存在。
 *
 * 独立 H2 库名，避免与其它上下文互相清表。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cache-fallback-it;DB_CLOSE_DELAY=-1",
        "hazmat.security.seed-users=false",
        "hazmat.ai.encode-on-startup=false",
        // 显式打开缓存，并把 Redis 指向空端口：每一次读写都会抛连接失败
        "hazmat.cache.chemical.enabled=true",
        "spring.data.redis.port=6399",
        "spring.data.redis.timeout=200ms",
        "spring.data.redis.connect-timeout=200ms"
})
class ChemicalCacheFallbackTest {

    @Autowired private ChemicalService chemicalService;
    @Autowired private StockService stockService;
    @Autowired private ChemicalRepository chemicalRepository;

    private Long chemicalId;

    @BeforeEach
    void setUp() {
        chemicalRepository.deleteAll();
        Chemical saved = chemicalRepository.save(Chemical.builder()
                .casNo("67-56-1")
                .name("甲醇")
                .category("易燃")
                .hazardLevel("高")
                .unit("mL")
                .spec("AR 500mL")
                .postOpenDays(90)
                .build());
        chemicalId = saved.getId();
    }

    @Test
    @DisplayName("Redis 连不上时仍然能读到档案，且字段完整")
    void findByIdWorksWithoutRedis() {
        Chemical chemical = chemicalService.findById(chemicalId);

        assertThat(chemical.getName()).isEqualTo("甲醇");
        assertThat(chemical.getUnit()).isEqualTo("mL");
        assertThat(chemical.getPostOpenDays()).isEqualTo(90);
    }

    @Test
    @DisplayName("不存在的 id 仍然抛 404，而不是把 Redis 的连接异常漏给接口")
    void missingChemicalStillGivesNotFound() {
        assertThatThrownBy(() -> chemicalService.findById(999_999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("试剂不存在");
    }

    @Test
    @DisplayName("库存视图在无 Redis 时照常组装")
    void stockViewWorksWithoutRedis() {
        var view = stockService.stockView(chemicalId);

        assertThat(view.chemicalName()).isEqualTo("甲醇");
        assertThat(view.unit()).isEqualTo("mL");
        assertThat(view.batches()).isEmpty();
    }
}
