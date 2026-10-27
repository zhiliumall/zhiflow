# zhiflow-common-test

单元测试基建。对标 yudao 的 `yudao-spring-boot-starter-test`，**照其思路自建，不引入 yudao jar**
（那些 jar 只存在于本地 m2，CI 编译不过，2026-06 已专门做过一轮移除）。

## 引入

```xml
<dependency>
    <groupId>com.zhiflow</groupId>
    <artifactId>zhiflow-common-test</artifactId>
    <version>${project.version}</version>
    <scope>test</scope>
</dependency>
```

⚠️ 单模块跑测试要带 `-am`，否则依赖从 `~/.m2` 取陈旧 jar：

```bash
./mvnw test -pl zhiflow-mall/zhiflow-mall-admin -am -Dtest=XxxTest
```

---

## `BaseMockitoUnitTest`

```java
class FooServiceTest extends BaseMockitoUnitTest {
    @Mock private FooMapper fooMapper;
}
```

**保留 Mockito 默认的 STRICT_STUBS**。「打了桩却没被调用」通常意味着这个测试没在测你以为的东西
（方法名写错、分支没走到），静默放过等于把一类真 bug 变成看不见。确需放宽的测试自己加：

```java
@MockitoSettings(strictness = Strictness.LENIENT)
```

---

## `MyBatisPlusTestSupport`

纯单测里用 `LambdaQueryWrapper` 会踩两个坑，这个类把它们封死。

### 坑 1：`can not find lambda cache for this entity`

Wrapper 生成 SQL 要查 MyBatis-Plus 的 TableInfo 缓存，那份缓存平时由 `SqlSessionFactory`
启动时填充；纯单测没有容器。

```java
@BeforeAll
static void setUp() {
    MyBatisPlusTestSupport.register(CouponUser.class, GoodsSpu.class);
}
```

幂等，多个测试类各自调用互不干扰。

### 坑 2：绑定参数是懒生成的（**这个坑不报错，只让断言静默失效**）

`eq()` 存进去的是 lambda，参数要等 SQL 片段真正 materialize 才落进 `paramNameValuePairs`。
直接读 `getParamNameValuePairs()` 拿到的是**空 map**——不抛异常，只是所有基于绑定值的断言恒为 false。
S5 实测：靠绑定值区分两次调用的桩，两次都判 false，返回值全串了，花了两轮才定位。

```java
MyBatisPlusTestSupport.sqlSegmentOf(wrapper);          // WHERE 片段
MyBatisPlusTestSupport.boundValuesOf(wrapper);         // 绑定参数（内部已 materialize）
MyBatisPlusTestSupport.boundValuesContain(wrapper, "1");
```

### 能力边界（重要）

`sqlSegmentOf` 只证明**列名出现过**，证明不了运算符对不对——`eq` 写成 `ne`，
`sql.contains("del_flag")` 照样通过。要证明语义得靠真库测试（见 `spike/`）。

---

## `RandomUtils`

基于 podam（yudao 同款）。**已验证在 JDK 25 可用**，能正确填充继承字段、`String[]`、
`LocalDateTime`、`BigDecimal`。

```java
CouponUser used = randomPojo(CouponUser.class, u -> {
    u.setCouponId("c-1");
    u.setStatus("1");        // 只设这个测试真正关心的字段
});

List<GoodsSpu> rows = randomPojoList(GoodsSpu.class, 3, (spu, i) -> spu.setName("row-" + i));
```

好处是测试代码里出现的字段就是**这个测试真正关心的字段**，噪音字段不会混进来误导读者。

**边界**：podam 靠反射递归填充，遇到自引用实体、`cn.hutool.json.JSONObject` 这类复杂字段可能很慢
或填出无意义的值。碰到就别硬用，直接 `new` 出来手工设几个字段更省事。

标量走 `manufacturePojo` 而非 `strategy.getTypeValue`——后者要求非 null 的 `AttributeMetadata`，
脱离字段上下文直接调会抛 `The attribute metadata inside the wrapper cannot be null`。

---

## `AssertUtils`

```java
AssertUtils.assertPojoEquals(expected, actual, "id", "createTime");
```

比 `assertEquals` 好在三点：不依赖实体正确实现 `equals`（本项目实体大量用 Lombok `@Data`，
继承体系下的 equals 语义容易出意外）；沿继承链比对（业务实体的 `delFlag` 在 `BaseEntity` 上）；
失败时**一次报出所有**不一致字段而不是撞到第一个就停。

`String[]` 按内容比（本项目有 `goods_spu.label` 等数组字段，`Objects.equals` 比的是引用）。

---

## `spike/`

档 2 的方言验证实验，**不是长期测试**，是可复现的实验记录。

`PostgresContainerSpikeTest` 在 Docker 不可用时自动跳过，不会让构建变红。要跑：

```bash
open -a Docker
./mvnw test -pl zhiflow-common/zhiflow-common-test -Dtest=PostgresContainerSpikeTest
```
