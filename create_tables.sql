-- Description: 创建 sustc 用户及所需数据表，并赋予相应权限

/* =========================
 * 1. 创建应用用户 sustc
 * ========================= */
DO $$
BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sustc') THEN
CREATE ROLE sustc LOGIN PASSWORD 'sustc';
END IF;
END $$;


/* =========================
 * 2. 创建数据表（owner 设为 sustc）
 * ========================= */

-- 给 sustc 用户创建 public schema 的创建权限
GRANT CREATE ON SCHEMA public TO sustc;

-- users 表存储用户信息
CREATE TABLE IF NOT EXISTS users (
                                     author_id   BIGSERIAL PRIMARY KEY,
                                     author_name VARCHAR(100) NOT NULL,
    gender      VARCHAR(10) CHECK (gender IN ('Male','Female','UNKNOWN')),
    age         INT,
    following   INT DEFAULT 0,   -- 派生属性
    followers   INT DEFAULT 0,   -- 派生属性
    password    VARCHAR(255) NOT NULL,
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE
    );
ALTER TABLE users OWNER TO sustc;

-- follows 表存储用户关注关系
CREATE TABLE IF NOT EXISTS follows (
                                       blogger_id  BIGINT REFERENCES users(author_id),
    follower_id BIGINT REFERENCES users(author_id),
    PRIMARY KEY (blogger_id, follower_id)
    );
ALTER TABLE follows OWNER TO sustc;

-- recipe 表存储食谱信息
CREATE TABLE IF NOT EXISTS recipe (
                                      recipe_id      BIGSERIAL PRIMARY KEY,
                                      author_id      BIGINT REFERENCES users(author_id),
    dish_name      VARCHAR(150) NOT NULL,
    date_published TIMESTAMP,
    cook_time      VARCHAR(50),
    prep_time      VARCHAR(50),
    description    TEXT,
    category       VARCHAR(100),
    aggr_rating    REAL CHECK (aggr_rating >= 0 AND aggr_rating <= 5), -- 派生
    review_cnt     INT DEFAULT 0,  -- 派生
    recipe_yield   VARCHAR(50),
    servings       INT,
    calories       REAL,
    fat            REAL,
    saturated_fat  REAL,
    cholesterol    REAL,
    sodium         REAL,
    carbohydrate   REAL,
    fiber          REAL,
    sugar          REAL,
    protein        REAL
    );
ALTER TABLE recipe OWNER TO sustc;

-- review 表存储食谱评论
CREATE TABLE IF NOT EXISTS review (
                                      review_id      BIGSERIAL PRIMARY KEY,
                                      recipe_id      BIGINT REFERENCES recipe(recipe_id),
    author_id      BIGINT REFERENCES users(author_id),
    rating         REAL,
    review         TEXT,
    date_submitted TIMESTAMP,
    date_modified  TIMESTAMP
    );
ALTER TABLE review OWNER TO sustc;

-- likes_review 表存储用户对评论的点赞关系
CREATE TABLE IF NOT EXISTS likes_review (
                                            author_id BIGINT REFERENCES users(author_id),
    review_id BIGINT REFERENCES review(review_id),
    PRIMARY KEY (author_id, review_id)
    );
ALTER TABLE likes_review OWNER TO sustc;

-- ingredient 表存储食材信息
CREATE TABLE IF NOT EXISTS ingredient (
                                          ingredient_id   BIGSERIAL PRIMARY KEY,
                                          ingredient_name VARCHAR(100) UNIQUE NOT NULL
    );
ALTER TABLE ingredient OWNER TO sustc;

-- has_ingredient 表存储食谱与食材的关联关系
CREATE TABLE IF NOT EXISTS has_ingredient (
                                              recipe_id     BIGINT REFERENCES recipe(recipe_id),
    ingredient_id BIGINT REFERENCES ingredient(ingredient_id),
    PRIMARY KEY (recipe_id, ingredient_id)
    );
ALTER TABLE has_ingredient OWNER TO sustc;


/* =========================
 * 3. 数据库与 schema 权限
 * ========================= */

GRANT CONNECT ON DATABASE sustc TO sustc;
GRANT USAGE ON SCHEMA public TO sustc;


/* =========================
 * 4. 序列 owner（BIGSERIAL 必须）
 * ========================= */

ALTER SEQUENCE users_author_id_seq OWNER TO sustc;
ALTER SEQUENCE recipe_recipe_id_seq OWNER TO sustc;
ALTER SEQUENCE review_review_id_seq OWNER TO sustc;
ALTER SEQUENCE ingredient_ingredient_id_seq OWNER TO sustc;


/* =========================
 * 5. 访问权限
 * ========================= */

GRANT SELECT, INSERT, UPDATE, DELETE
      ON ALL TABLES IN SCHEMA public
          TO sustc;

GRANT USAGE, SELECT
             ON ALL SEQUENCES IN SCHEMA public
                 TO sustc;


/* =========================
 * 6. 默认权限
 * ========================= */

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sustc;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO sustc;
