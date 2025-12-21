package io.sustc.service.impl;

import io.sustc.dto.AuthInfo;
import io.sustc.dto.PageResult;
import io.sustc.dto.RecipeRecord;
import io.sustc.dto.ReviewRecord;
import io.sustc.service.ReviewService;
import io.sustc.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class ReviewServiceImpl implements ReviewService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserService userService;

    @Override
    @Transactional
    public long addReview(AuthInfo auth, long recipeId, int rating, String review) {
        long userId = userService.login(auth);
        if (userId == -1) {
            throw new SecurityException("Invalid auth info");
        }
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }
        String recipeCheck = "SELECT count(*) FROM recipe WHERE recipe_id = ?";
        Long count = jdbcTemplate.queryForObject(recipeCheck, Long.class, recipeId);
        if (count == null || count == 0) {
            throw new IllegalArgumentException("Recipe does not exist");
        }

        Long reviewId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(review_id), 0) + 1 FROM review", Long.class);
        
        String insertSql = """
                INSERT INTO review (review_id, recipe_id, author_id, rating, review, date_submitted, date_modified) 
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(insertSql, reviewId, recipeId, userId, rating, review, now, now);

        refreshRecipeAggregatedRating(recipeId);
        return reviewId;
    }

    @Override
    @Transactional
    public void editReview(AuthInfo auth, long recipeId, long reviewId, int rating, String review) {
        long userId = userService.login(auth);
        if (userId == -1) {
            throw new SecurityException("Invalid auth info");
        }
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }

        String checkSql = "SELECT author_id, recipe_id FROM review WHERE review_id = ?";
        try {
            var map = jdbcTemplate.queryForMap(checkSql, reviewId);
            Long authorId = (Long) map.get("author_id");
            Long storedRecipeId = (Long) map.get("recipe_id");
            
            if (authorId != userId) {
                throw new SecurityException("User is not the author of this review");
            }
            if (storedRecipeId != recipeId) {
                throw new IllegalArgumentException("Review does not belong to the specified recipe");
            }
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Review does not exist");
        }

        String updateSql = "UPDATE review SET rating = ?, review = ?, date_modified = ? WHERE review_id = ?";
        jdbcTemplate.update(updateSql, rating, review, Timestamp.from(Instant.now()), reviewId);

        refreshRecipeAggregatedRating(recipeId);
    }

    @Override
    @Transactional
    public void deleteReview(AuthInfo auth, long recipeId, long reviewId) {
        long userId = userService.login(auth);
        if (userId == -1) {
            throw new SecurityException("Invalid auth info");
        }

        String checkSql = "SELECT author_id, recipe_id FROM review WHERE review_id = ?";
        try {
            var map = jdbcTemplate.queryForMap(checkSql, reviewId);
            Long authorId = (Long) map.get("author_id");
            Long storedRecipeId = (Long) map.get("recipe_id");
            
            if (authorId != userId) {
                throw new SecurityException("User is not the author of this review");
            }
            if (storedRecipeId != recipeId) {
                throw new IllegalArgumentException("Review does not belong to the specified recipe");
            }
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Review does not exist");
        }

        jdbcTemplate.update("DELETE FROM likes_review WHERE review_id = ?", reviewId);
        jdbcTemplate.update("DELETE FROM review WHERE review_id = ?", reviewId);

        refreshRecipeAggregatedRating(recipeId);
    }

    @Override
    @Transactional
    public long likeReview(AuthInfo auth, long reviewId) {
        long userId = userService.login(auth);
        if (userId == -1) {
            throw new SecurityException("Invalid auth info");
        }

        try {
            Long authorId = jdbcTemplate.queryForObject("SELECT author_id FROM review WHERE review_id = ?", Long.class, reviewId);
            if (authorId == userId) {
                throw new SecurityException("Cannot like own review");
            }
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Review does not exist");
        }

        String sql = "INSERT INTO likes_review (author_id, review_id) VALUES (?, ?) ON CONFLICT (author_id, review_id) DO NOTHING";
        jdbcTemplate.update(sql, userId, reviewId);

        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM likes_review WHERE review_id = ?", Long.class, reviewId);
    }

    @Override
    @Transactional
    public long unlikeReview(AuthInfo auth, long reviewId) {
        long userId = userService.login(auth);
        if (userId == -1) {
            throw new SecurityException("Invalid auth info");
        }

        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM review WHERE review_id = ?", Long.class, reviewId);
        if (count == 0) {
            throw new IllegalArgumentException("Review does not exist");
        }

        jdbcTemplate.update("DELETE FROM likes_review WHERE author_id = ? AND review_id = ?", userId, reviewId);
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM likes_review WHERE review_id = ?", Long.class, reviewId);
    }

    @Override
    public PageResult<ReviewRecord> listByRecipe(long recipeId, int page, int size, String sort) {
        if (page < 1 || size <= 0) {
            throw new IllegalArgumentException("Page must be >= 1 and size > 0");
        }
        
        StringBuilder sql = new StringBuilder("""
                SELECT r.review_id, r.recipe_id, r.author_id, u.author_name, r.rating, r.review, 
                       r.date_submitted, r.date_modified 
                FROM review r 
                LEFT JOIN users u ON r.author_id = u.author_id 
                WHERE r.recipe_id = ? 
                """);

        if ("likes_desc".equals(sort)) {
            sql.append(" ORDER BY (SELECT COUNT(*) FROM likes_review lr WHERE lr.review_id = r.review_id) DESC, r.review_id ASC ");
        } else if ("date_desc".equals(sort)) {
            sql.append(" ORDER BY r.date_modified DESC, r.review_id ASC ");
        } else {
             sql.append(" ORDER BY r.review_id ASC ");
        }

        sql.append(" LIMIT ? OFFSET ? ");

        List<ReviewRecord> list = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            ReviewRecord r = new ReviewRecord();
            r.setReviewId(rs.getLong("review_id"));
            r.setRecipeId(rs.getLong("recipe_id"));
            r.setAuthorId(rs.getLong("author_id"));
            r.setAuthorName(rs.getString("author_name"));
            r.setRating(rs.getFloat("rating"));
            r.setReview(rs.getString("review"));
            r.setDateSubmitted(rs.getTimestamp("date_submitted"));
            r.setDateModified(rs.getTimestamp("date_modified"));
            r.setLikes(new long[0]); 
            return r;
        }, recipeId, size, (page - 1) * size);

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM review WHERE recipe_id = ?", Long.class, recipeId);

        return PageResult.<ReviewRecord>builder()
                .items(list)
                .page(page)
                .size(size)
                .total(total == null ? 0 : total)
                .build();
    }

    @Override
    @Transactional
    public RecipeRecord refreshRecipeAggregatedRating(long recipeId) {
         String checkSql = "SELECT recipe_id FROM recipe WHERE recipe_id = ?";
         try {
             jdbcTemplate.queryForObject(checkSql, Long.class, recipeId);
         } catch (EmptyResultDataAccessException e) {
             throw new IllegalArgumentException("Recipe does not exist");
         }

         // Use NUMERIC in DB to ensure exact rounding matching expected results
         String updateSql = """
                 UPDATE recipe 
                 SET aggr_rating = ROUND(CAST((SELECT AVG(rating) FROM review WHERE recipe_id = ?) AS numeric), 2), 
                     review_cnt = (SELECT COUNT(*) FROM review WHERE recipe_id = ?) 
                 WHERE recipe_id = ?
                 """;
         jdbcTemplate.update(updateSql, recipeId, recipeId, recipeId);

         String fetchSql = "SELECT aggr_rating, review_cnt FROM recipe WHERE recipe_id = ?";
         return jdbcTemplate.queryForObject(fetchSql, (rs, rowNum) -> 
             RecipeRecord.builder()
                 .RecipeId(recipeId)
                 .aggregatedRating(rs.getFloat("aggr_rating"))
                 .reviewCount(rs.getInt("review_cnt"))
                 .build()
         , recipeId);
    }
}
