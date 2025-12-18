-- users实体
CREATE TABLE users (
                       author_id      SERIAL PRIMARY KEY,
                       author_name    VARCHAR(100) NOT NULL,
                       gender         VARCHAR(10)  CHECK (gender IN ('Male','Female')),
                       age            INT,
                       following_cnt  INT,   --  派生属性
                       follower_cnt   INT,    -- 派生属性
                       password       VARCHAR(100) NOT NULL
);
-- 自连接：关注关系
CREATE TABLE follows (
                         blogger_id  INT REFERENCES users(author_id), --被关注者
                         follower_id INT REFERENCES users(author_id), --粉丝
                         PRIMARY KEY (blogger_id, follower_id)
);
--recipe实体
CREATE TABLE recipe (
                        recipe_id       SERIAL PRIMARY KEY,
                        author_id       INT REFERENCES users(author_id),
                        dish_name       VARCHAR(150) NOT NULL,
                        date_published  DATE,
                        cook_time       INTERVAL,
                        prep_time       INTERVAL,
                        description     TEXT,
                        category        VARCHAR(100),
                        aggr_rating     DECIMAL(3,2), --派生
                        review_cnt      INT,       -- 派生
                        yield           VARCHAR(50), --分量（带计量单位）
                        servings       VARCHAR(50), --几个人吃
                        calories        DECIMAL(8,2),
                        fat             DECIMAL(8,2),
                        saturated_fat   DECIMAL(8,2),
                        cholesterol     DECIMAL(8,2),
                        sodium          DECIMAL(8,2),
                        carbohydrate    DECIMAL(8,2),
                        fiber           DECIMAL(8,2),
                        sugar           DECIMAL(8,2),
                        protein         DECIMAL(8,2)
);

--review实体
CREATE TABLE review (
                        review_id     SERIAL PRIMARY KEY,
                        recipe_id     INT REFERENCES recipe(recipe_id),
                        author_id     INT REFERENCES users(author_id),
                        rating        DECIMAL(3,2),
                        review_text   TEXT,
                        date_submit   DATE,
                        date_modify   DATE
);
-- user likes review，多对多
CREATE TABLE likes_review (
                              author_id INT REFERENCES users(author_id),
                              review_id INT REFERENCES review(review_id),
                              PRIMARY KEY (author_id, review_id)
);
-- ingredient实体
CREATE TABLE ingredient (
                            ingredient_id   SERIAL PRIMARY KEY,
                            ingredient_name VARCHAR(100) UNIQUE NOT NULL
);
-- recipe has ingredient，多对多
CREATE TABLE has_ingredient (
                                recipe_id    INT REFERENCES recipe(recipe_id),
                                ingredient_id INT REFERENCES ingredient(ingredient_id),
                                PRIMARY KEY (recipe_id, ingredient_id)
);