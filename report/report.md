# SUSTech CS307 Project Part Ⅱ — Report

## 一、群组信息

### 成员信息

- **成员1**: 刘以煦 12410148 周三56节lab
- **成员2**: 刘君昊 12410303 周四34节lab

### 分工

---

## 二、数据库设计

### E-R 图

![ER diagram](ER_Diagram_Project_2.png)

### 数据库图

![diagram_visualization](diagram_visualization_project_2.png)

### 表格设计

#### 1. users表

  **主键**：`author_id`

  **业务字段**：

- `author_name` ：用户名（强制非空）

- `gender` ：性别枚举约束，保证取值合法

- `age` ：年龄（可空，允许未知）

- `password` ：密码

  **派生/冗余字段**：

- `following`：关注数（可由 follows 统计得到）

- `followers`：粉丝数（可由 follows 统计得到）

  **软删除**：

- `is_deleted`：逻辑删除标记，避免物理删除导致外键级联问题、也便于审计

#### 2. follows表

  **说明**：表示用户之间的关注关系，自连接实现（多对多）
  **主键**：`(blogger_id, follower_id)`  
  **外键**：  

- `blogger_id` → `users(author_id)` （被关注者）  
- `follower_id` → `users(author_id)` （粉丝）

#### 3. recipe表

  **主键**：`recipe_id`  
  **外键**：`author_id` → `users(author_id)`  

  **核心字段**：

- `dish_name`：菜品名
- `date_published`：发布日期  
- `cook_time` / `prep_time`：烹饪/准备时长
- `servings`：可供人数
- `description`：描述
- `category`：分类

  **派生/统计字段**：

- `aggr_rating`：聚合评分（来自 review）
- `review_cnt`：评论数（来自 review）

  **营养信息字段**：

- `calories`：卡路里
- `fat` / `saturated_fat` / `cholesterol` / `sodium` / `carbohydrate` / `fiber` / `sugar` / `protein`：营养信息

#### 4. review表

  **主键**：`review_id`  
  **外键**：  

- `recipe_id` → `recipe(recipe_id)`  
- `author_id` → `users(author_id)`  
  **属性**：  
- `rating`：评分  
- `review_text`：评论内容  
- `date_submitted`：提交日期  
- `date_modified`：修改日期

#### 5. likes_review表

  **说明**：用户点赞评论的关系表（多对多）
  **主键**：`(author_id, review_id)`  
  **外键**：  

- `author_id` → `users(author_id)`  
- `review_id` → `review(review_id)`

#### 6. ingredient表

  **主键**：`ingredient_id`
  **属性**：  

- `ingredient_name`：食材名（唯一）

#### 7. has_ingredient表

  **说明**：`recipe` 与 `ingredient` 的多对多关系表
  **主键**：`(recipe_id, ingredient_id)`  
  **外键**：  

- `recipe_id` → `recipe(recipe_id)`  
- `ingredient_id` → `ingredient(ingredient_id)`

### 用户创建与权限设置

本系统在数据库层面创建了专用应用用户 **`sustc`**（密码：`sustc`），用于后端程序通过 JDBC 连接数据库并执行所有业务相关操作。该用户作为唯一的应用访问入口，避免后端程序直接使用数据库超级用户。

在权限设计上，系统遵循 **最小权限原则（Principle of Least Privilege）**，并结合实际业务需求进行精细化授权：

- 授予用户 `sustc` 数据库级 **`CONNECT`** 权限，允许其连接数据库 `sustc`；
- 授予 `public` schema 的 **`USAGE`** 与 **`CREATE`** 权限，使应用用户能够在默认模式下创建与管理业务表结构（用于初始化与基准测试数据导入）；
- 将所有业务表及由 `BIGSERIAL` 生成的序列对象的 **所有权（OWNER）** 统一赋予用户 `sustc`，确保其能够正常执行表的创建、删除与数据重置操作；
- 授予所有业务表 **`SELECT / INSERT / UPDATE / DELETE`** 权限，支持用户注册、菜谱发布、评论、点赞与关注等核心功能；
- 授予所有序列对象 **`USAGE / SELECT`** 权限，保证自增主键在数据插入过程中的正确生成；
- 通过 **`ALTER DEFAULT PRIVILEGES`** 机制，为未来新建的表与序列自动配置相同权限，避免后续权限缺失问题。

数据库超级用户 **`postgres`** 仅用于数据库初始化、用户创建及紧急维护操作，不参与日常业务访问。该设计在保证系统安全性的同时，也满足了项目对性能测试与数据重置的实际需求。

---

## 三、基础API实现

## 三、基础API实现

#### updateProfile

- **引入基于内存的登录结果缓存机制**：系统使用映射结构缓存 `authorId` 对应的认证结果，使同一用户在多次调用接口时无需重复访问数据库进行身份校验，显著减少了高频鉴权场景下的数据库压力。
- **采用固定结构的更新语句**：利用 `COALESCE` 函数结合 `UPDATE` 语句仅修改非空字段。这种方式避免了 Java 层面的动态 SQL 拼接，使数据库能够复用执行计划（Prepared Statement），降低了解析成本，且由数据库自身保证单条语句的原子性。

#### register

- **内存原子ID生成策略**：弃用了低效且在高并发下存在严重锁竞争风险的 `SELECT MAX(author_id)` 方式。改为在内存中维护一个 `AtomicLong` 计数器（首次使用时懒加载数据库当前最大值），后续 ID 生成完全在内存中通过无锁 CAS（Compare-And-Swap）操作完成，将 ID 获取的时间复杂度降至 O(1) 且零数据库交互。
- **乐观插入机制（Optimistic Insertion）**：移除了用于检查重名的 `SELECT COUNT(*)` 查询。利用数据库 `author_name` 字段的唯一约束（Unique Constraint），直接执行 `INSERT` 语句。仅在极少数发生冲突的情况下捕获 `DuplicateKeyException`，将注册流程的数据库交互次数从 3 次（查重、查Max、插入）减少为 1 次，大幅降低了网络往返（RTT）开销。
- **静态资源复用**：将 `DateTimeFormatter` 提取为静态常量，避免了在高频调用的注册接口中重复创建昂贵的日期解析对象，降低了 CPU 计算开销和 GC 内存回收压力。

#### deleteAccount

- **集合更新（Set-based Update）消除 N+1 问题**：原逻辑需先查询关注列表，再在应用层循环执行 SQL 更新每一个相关用户的计数器（若用户关注 1000 人则产生 1000+ 次数据库交互）。优化后，利用 SQL 的 `WHERE ... IN (SELECT ...)` 子句，将成百上千次更新操作合并为 2 条批量更新 SQL。无论用户数据量多大，数据库交互次数保持恒定，性能提升显著。
- **原子性状态流转**：通过执行 `UPDATE ... SET is_deleted=true ...` 并检查返回的受影响行数， 原子性地判断用户是否存在及是否已删除。减少了一次网络 IO，还利用数据库行锁（Row Lock）避免了并发场景下的重复删除问题。
- **事务一致性保障**：鉴于注销操作涉及 `users` 表的状态变更、`follows` 表的记录清理以及关联用户计数器的级联更新，该方法严格使用了 `@Transactional` 注解，确保在批量操作过程中的数据强一致性。


