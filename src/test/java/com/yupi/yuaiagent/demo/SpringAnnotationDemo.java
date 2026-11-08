package com.yupi.yuaiagent.demo;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Spring 核心注解全演示（糖浆场景）
 *
 * 启动顺序：
 *   Step1. 扫描 @Configuration → 执行 @Bean 方法 → 对象存入容器
 *   Step2. 扫描 @Autowired/@Resource → 从容器取对象 → 注入字段
 *   Step3. 测试方法运行（字段已就绪）
 *
 * 常用注解速查：
 *   @SpringBootTest     测试类启动完整 Spring 容器
 *   @Configuration      配置类（内有 @Bean 方法）
 *   @Bean               把方法返回值放进容器，Bean名=方法名
 *   @Component          把自己写的类放进容器（替代 @Bean 的简便方式）
 *   @Service/@Repository/@Controller  同 @Component，语义细分
 *   @Primary            同类型多个 Bean 时的默认首选
 *   @Autowired          按【类型】注入
 *   @Qualifier          配合 @Autowired 按名字精确注入
 *   @Resource           按【名字】注入（Java标准，不是Spring专属）
 *   @Value("${key}")    注入配置文件里的值
 */
@SpringBootTest
public class SpringAnnotationDemo {

    // ══════════════════════════════════════════════════════════════
    // Part1：接口 + 两个实现
    //
    // 【static 内部类 vs 普通内部类】
    //
    // 这里的"外层"= SpringAnnotationDemo，"内层"= StrawberrySyrup
    //
    // ❌ 不加 static（普通内部类）：
    //      Java 规定：必须先有外层对象，才能 new 内层
    //      SpringAnnotationDemo outer = new SpringAnnotationDemo();
    //      StrawberrySyrup s = outer.new StrawberrySyrup();  ← 这样才行
    //      Spring 不知道怎么先帮你 new SpringAnnotationDemo → 直接报错
    //
    // ✅ 加 static（静态内部类）：
    //      和外层类脱离关系，可以独立 new
    //      StrawberrySyrup s = new StrawberrySyrup();  ← Spring 直接这样做
    //
    // 实际项目：配置类都是独立文件，不写在别的类里，根本不存在这个问题
    // 这里只是把所有演示代码塞在一个文件里，才需要加 static
    // ══════════════════════════════════════════════════════════════

    interface Syrup { String taste(); }

    static class StrawberrySyrup implements Syrup {
        @Override public String taste() { return "草莓味糖浆"; }
    }

    static class MatchaSyrup implements Syrup {
        @Override public String taste() { return "抹茶味糖浆"; }
    }

    static class LemonSyrup implements Syrup {
        @Override public String taste() { return "柠檬味糖浆"; }
    }

    // ══════════════════════════════════════════════════════════════
    // Part2：@Configuration + @Bean 注册 Bean
    //
    // @Bean 注意事项：
    //   ✅ 适合：第三方库的类（源码改不了，只能手动 new）
    //   ✅ 适合：需要复杂初始化逻辑的对象
    //   ❌ 错误：@Bean 方法返回 void（没有对象可放入容器）
    //   ❌ 错误：@Bean 写在普通类（非@Configuration）里，Bean不会被注册
    //
    // @Component 替代 @Bean：
    //   ✅ 自己写的类：直接在类上加 @Component，比 @Bean 更简单
    //   ❌ 第三方库的类：源码改不了，只能用 @Bean
    // ══════════════════════════════════════════════════════════════

    @Configuration   // Step1：Spring 启动时扫描到此类 → 执行里面所有 @Bean 方法
    static class SyrupConfig {  // static：让 Spring 能直接 new 此配置类

        @Bean  // Bean名 = 方法名 "strawberrySyrup"
        public Syrup strawberrySyrup() { return new StrawberrySyrup(); }

        @Bean  // Bean名 = "matchaSyrup"
        public Syrup matchaSyrup() { return new MatchaSyrup(); }

        @Bean  // Bean名 = "lemonSyrup"
        public Syrup lemonSyrup() { return new LemonSyrup(); }

        @Bean
        @Primary  // 同类型有多个时，@Autowired 不指定名字就给这个
        public Syrup defaultSyrup() { return new StrawberrySyrup(); }
    }

    // ══════════════════════════════════════════════════════════════
    // Part3：@Autowired 按【类型】注入
    //
    // 核心：Spring 看的是字段声明的【类型】，不是变量名
    //
    //   private Syrup xxx         → 找 Syrup 类型的 Bean
    //   private StrawberrySyrup x → 找 StrawberrySyrup 类型的 Bean（更精确）
    //
    // 多个同类型 Bean 时的处理规则：
    //   有 @Primary → 给 @Primary 那个
    //   无 @Primary → 报错（NoUniqueBeanDefinitionException）
    //
    // ❌ 常见错误：容器里有多个同类型Bean，既没 @Primary 也没 @Qualifier → 启动报错
    // ══════════════════════════════════════════════════════════════

    // 示例A：字段类型=Syrup（接口） → 找所有 Syrup Bean → 给 @Primary
    @Autowired
    private Syrup autowiredSyrup;                    // 拿到：defaultSyrup（草莓）

    // 示例B：字段类型=StrawberrySyrup（具体类） → 只有1个这个类型 → 直接注入
    // 体现"按类型"：换成具体类型，就精确匹配，不需要 @Primary
    @Autowired
    private StrawberrySyrup concreteTypeSyrup;       // 拿到：strawberrySyrup（草莓）

    // 示例C：@Autowired + @Qualifier → 先按类型过滤，再按名字精确拿
    // @Primary 在这里无效
    @Autowired
    @Qualifier("matchaSyrup")
    private Syrup qualifierSyrup;                    // 拿到：matchaSyrup（抹茶）

    // ══════════════════════════════════════════════════════════════
    // Part4：@Resource 按【名字】注入
    //
    // 与 @Autowired 的区别：
    //   @Autowired：先看类型，多个时看 @Primary / @Qualifier
    //   @Resource ：直接按名字，跳过类型判断，@Primary 对它无效
    //
    // ❌ 常见错误：@Resource(name="xxx") 名字写错 → 启动报错（NoSuchBeanDefinitionException）
    // ══════════════════════════════════════════════════════════════

    @Resource(name = "lemonSyrup")  // 精确按名字，不受 @Primary 影响
    private Syrup resourceSyrup;                     // 拿到：lemonSyrup（柠檬）

    // ══════════════════════════════════════════════════════════════
    // Part5：测试验证
    // ══════════════════════════════════════════════════════════════

    @Test
    void testAnnotations() {
        System.out.println("━━ @Autowired 按类型（接口）→ 给 @Primary ━━");
        System.out.println("  " + autowiredSyrup.taste());                   // 草莓（@Primary）

        System.out.println("\n━━ @Autowired 按类型（具体类）→ 唯一匹配直接注入 ━━");
        System.out.println("  " + concreteTypeSyrup.taste());                // 草莓（唯一StrawberrySyrup）
        System.out.println("  字段类型换成具体类，Spring只找该类型 → 不需要@Primary");

        System.out.println("\n━━ @Autowired + @Qualifier → 按名字精确 ━━");
        System.out.println("  " + qualifierSyrup.taste());                   // 抹茶

        System.out.println("\n━━ @Resource 按名字 → @Primary无效 ━━");
        System.out.println("  " + resourceSyrup.taste());                    // 柠檬

        System.out.println("\n━━ 注解速查 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("  @Configuration          配置类，执行内部所有@Bean");
        System.out.println("  @Bean                   方法返回值→容器，Bean名=方法名");
        System.out.println("  @Component/Service/...  自己的类→容器（比@Bean简单）");
        System.out.println("  @Primary                多个同类型时的默认Bean");
        System.out.println("  @Autowired              按【类型】注入");
        System.out.println("  @Autowired+@Qualifier   按【类型+名字】注入");
        System.out.println("  @Resource(name)         按【名字】注入，忽略@Primary");
        System.out.println("  @Value(\"${key}\")         注入配置文件的值");
    }
}
