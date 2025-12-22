-- users实体
CREATE TABLE users (
                       author_id      BIGSERIAL PRIMARY KEY,
                       author_name    VARCHAR(100) NOT NULL,
                       gender         VARCHAR(10)  CHECK (gender IN ('Male','Female','UNKNOWN')),
                       age            INT,
                       following      INT DEFAULT 0,   --  派生属性
                       followers      INT DEFAULT 0,    -- 派生属性
                       password       VARCHAR(255) NOT NULL,
                       is_deleted     BOOLEAN NOT NULL DEFAULT FALSE
);
-- 自连接：关注关系
CREATE TABLE follows (
                         blogger_id  BIGINT REFERENCES users(author_id), --被关注者
                         follower_id BIGINT REFERENCES users(author_id), --粉丝
                         PRIMARY KEY (blogger_id, follower_id)
);
--recipe实体
CREATE TABLE recipe (
                        recipe_id       BIGSERIAL PRIMARY KEY,
                        author_id       BIGINT REFERENCES users(author_id),
                        dish_name       VARCHAR(150) NOT NULL,
                        date_published  TIMESTAMP,
                        cook_time       VARCHAR(50),
                        prep_time       VARCHAR(50),
                        description     TEXT,
                        category        VARCHAR(100),
                        aggr_rating     REAL CHECK (aggr_rating >= 0 AND aggr_rating <= 5), --派生
                        review_cnt      INT DEFAULT 0,         -- 派生
                        recipe_yield    VARCHAR(50), --分量（带计量单位）
                        servings        INT, --几个人吃
                        calories        REAL,
                        fat             REAL,
                        saturated_fat   REAL,
                        cholesterol     REAL,
                        sodium          REAL,
                        carbohydrate    REAL,
                        fiber           REAL,
                        sugar           REAL,
                        protein         REAL
);

--review实体
CREATE TABLE review (
                        review_id        BIGSERIAL PRIMARY KEY,
                        recipe_id        BIGINT REFERENCES recipe(recipe_id),
                        author_id        BIGINT REFERENCES users(author_id),
                        rating           REAL,
                        review           TEXT,
                        date_submitted   TIMESTAMP,
                        date_modified    TIMESTAMP
);
-- user likes review，多对多
CREATE TABLE likes_review (
                              author_id BIGINT REFERENCES users(author_id),
                              review_id BIGINT REFERENCES review(review_id),
                              PRIMARY KEY (author_id, review_id)
);
-- ingredient实体
CREATE TABLE ingredient (
                            ingredient_id   BIGSERIAL PRIMARY KEY,
                            ingredient_name VARCHAR(100) UNIQUE NOT NULL
);
-- recipe has ingredient，多对多
CREATE TABLE has_ingredient (
                                recipe_id    BIGINT REFERENCES recipe(recipe_id),
                                ingredient_id BIGINT REFERENCES ingredient(ingredient_id),
                                PRIMARY KEY (recipe_id, ingredient_id)
);

-- 1) 创建应用连接用户 sustc（若已存在则不重复创建）
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sustc') THEN
CREATE ROLE sustc LOGIN PASSWORD 'sustec';
END IF;
END $$;

-- 2) 允许连接数据库
GRANT CONNECT ON DATABASE sustc TO sustc;

-- 3) 允许使用 schema（默认 public）
GRANT USAGE ON SCHEMA public TO sustc;

-- 4) 对当前已有的表授予 DML 权限（读写）
GRANT SELECT, INSERT, UPDATE, DELETE
      ON ALL TABLES IN SCHEMA public
          TO sustc;

-- 5) BIGSERIAL 会生成 sequence；插入时需要 sequence 权限
GRANT USAGE, SELECT
             ON ALL SEQUENCES IN SCHEMA public
                 TO sustc;

-- 6) 未来新建表/序列时自动赋权
ALTER DEFAULT PRIVILEGES IN SCHEMA public
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sustc;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
GRANT USAGE, SELECT ON SEQUENCES TO sustc;
