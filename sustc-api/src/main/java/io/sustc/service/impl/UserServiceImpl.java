package io.sustc.service.impl;

import io.sustc.dto.*;
import io.sustc.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@Service
@Slf4j
public class UserServiceImpl implements UserService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public long register(RegisterUserReq req) {
        if (req.getName() == null || req.getName().isEmpty() || req.getPassword() == null || req.getPassword().isEmpty()) {
            return -1;
        }
        if (req.getGender() == null || req.getGender() == RegisterUserReq.Gender.UNKNOWN) {
            return -1;
        }
        
        if (req.getBirthday() == null || req.getBirthday().isEmpty()) {
            return -1;
        }
        Integer age = calculateAge(req.getBirthday());
        if (age == null || age <= 0) {
            return -1;
        }

        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE author_name = ?", Long.class, req.getName());
        if (count != null && count > 0) {
            return -1;
        }

        Long newId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(author_id), 0) + 1 FROM users", Long.class);

        String genderStr = req.getGender() == RegisterUserReq.Gender.MALE ? "Male" : "Female";

        String sql = "INSERT INTO users (author_id, author_name, gender, age, following, followers, password, is_deleted) VALUES (?, ?, ?, ?, 0, 0, ?, false)";
        jdbcTemplate.update(sql, newId, req.getName(), genderStr, age, req.getPassword());

        return newId;
    }

    private Integer calculateAge(String birthday) {
        String[] patterns = {"yyyy-MM-dd", "MM/dd/yyyy", "yyyy/MM/dd", "yyyy-MM-dd HH:mm:ss", "MM/dd/yyyy HH:mm:ss"};
        for (String pattern : patterns) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern, Locale.US);
                LocalDate birthDate;
                if (pattern.contains("HH")) {
                    birthDate = java.time.LocalDateTime.parse(birthday, formatter).toLocalDate();
                } else {
                    birthDate = LocalDate.parse(birthday, formatter);
                }
                return Period.between(birthDate, LocalDate.now()).getYears();
            } catch (Exception e) {
                // continue to next pattern
            }
        }
        return null;
    }

    @Override
    public long login(AuthInfo auth) {
        if (auth == null || auth.getPassword() == null || auth.getPassword().isEmpty()) {
            return -1;
        }
        String sql = "SELECT password, is_deleted FROM users WHERE author_id = ?";
        try {
            Map<String, Object> map = jdbcTemplate.queryForMap(sql, auth.getAuthorId());
            boolean isDeleted = (Boolean) map.get("is_deleted");
            String pwd = (String) map.get("password");
            
            if (isDeleted || !auth.getPassword().equals(pwd)) {
                return -1;
            }
            return auth.getAuthorId();
        } catch (EmptyResultDataAccessException e) {
            return -1;
        }
    }

    @Override
    @Transactional
    public boolean deleteAccount(AuthInfo auth, long userId) {
        long loggedInId = login(auth);
        if (loggedInId == -1) {
            throw new SecurityException("Invalid auth info");
        }
        if (loggedInId != userId) {
            throw new SecurityException("Cannot delete other user's account");
        }

        Boolean isDeleted = jdbcTemplate.queryForObject("SELECT is_deleted FROM users WHERE author_id = ?", Boolean.class, userId);
        if (Boolean.TRUE.equals(isDeleted)) {
            return false;
        }

        jdbcTemplate.update("UPDATE users SET is_deleted = true WHERE author_id = ?", userId);

        List<Long> followingIds = jdbcTemplate.query("SELECT blogger_id FROM follows WHERE follower_id = ?", (rs, rowNum) -> rs.getLong(1), userId);
        for (Long targetId : followingIds) {
            jdbcTemplate.update("UPDATE users SET followers = followers - 1 WHERE author_id = ?", targetId);
        }

        List<Long> followerIds = jdbcTemplate.query("SELECT follower_id FROM follows WHERE blogger_id = ?", (rs, rowNum) -> rs.getLong(1), userId);
        for (Long sourceId : followerIds) {
            jdbcTemplate.update("UPDATE users SET following = following - 1 WHERE author_id = ?", sourceId);
        }

        jdbcTemplate.update("DELETE FROM follows WHERE follower_id = ? OR blogger_id = ?", userId, userId);
        jdbcTemplate.update("UPDATE users SET following = 0, followers = 0 WHERE author_id = ?", userId);

        return true;
    }

    @Override
    @Transactional
    public boolean follow(AuthInfo auth, long followeeId) {
        long followerId = login(auth);
        if (followerId == -1) {
            throw new SecurityException("Invalid auth info");
        }
        if (followerId == followeeId) {
            throw new SecurityException("Cannot follow yourself");
        }

        try {
             Boolean isDeleted = jdbcTemplate.queryForObject("SELECT is_deleted FROM users WHERE author_id = ?", Boolean.class, followeeId);
             if (Boolean.TRUE.equals(isDeleted)) return false;
        } catch (EmptyResultDataAccessException e) {
             return false;
        }

        String checkSql = "SELECT COUNT(*) FROM follows WHERE follower_id = ? AND blogger_id = ?";
        Long count = jdbcTemplate.queryForObject(checkSql, Long.class, followerId, followeeId);

        if (count != null && count > 0) {
            jdbcTemplate.update("DELETE FROM follows WHERE follower_id = ? AND blogger_id = ?", followerId, followeeId);
            jdbcTemplate.update("UPDATE users SET following = following - 1 WHERE author_id = ?", followerId);
            jdbcTemplate.update("UPDATE users SET followers = followers - 1 WHERE author_id = ?", followeeId);
            return false;
        } else {
            jdbcTemplate.update("INSERT INTO follows (follower_id, blogger_id) VALUES (?, ?)", followerId, followeeId);
            jdbcTemplate.update("UPDATE users SET following = following + 1 WHERE author_id = ?", followerId);
            jdbcTemplate.update("UPDATE users SET followers = followers + 1 WHERE author_id = ?", followeeId);
            return true;
        }
    }

    @Override
    public UserRecord getById(long userId) {
        String sql = "SELECT author_id, author_name, gender, age, followers, following, password, is_deleted FROM users WHERE author_id = ?";
        try {
            return jdbcTemplate.queryForObject(sql, new BeanPropertyRowMapper<>(UserRecord.class), userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    @Transactional
    public void updateProfile(AuthInfo auth, String gender, Integer age) {
        long userId = login(auth);
        if (userId == -1) {
            throw new SecurityException("Invalid auth info");
        }

        StringBuilder sql = new StringBuilder("UPDATE users SET ");
        List<Object> args = new ArrayList<>();
        if (gender != null && !gender.isEmpty()) {
            sql.append("gender = ?, ");
            args.add(gender);
        }
        if (age != null) {
             if (age <= 0) {
                 // invalid age
             }
             sql.append("age = ?, ");
             args.add(age);
        }

        if (args.isEmpty()) return;

        sql.setLength(sql.length() - 2);
        sql.append(" WHERE author_id = ?");
        args.add(userId);

        jdbcTemplate.update(sql.toString(), args.toArray());
    }

    @Override
    public PageResult<FeedItem> feed(AuthInfo auth, int page, int size, String category) {
        long userId = login(auth);
        if (userId == -1) {
            throw new SecurityException("Invalid auth info");
        }
        if (page < 1) page = 1;
        if (size <= 0) size = 10;
        if (size > 200) size = 200;

        StringBuilder sqlBuilder = new StringBuilder("""
                SELECT r.recipe_id, r.dish_name as name, r.author_id, u.author_name, r.date_published, 
                       r.aggr_rating as aggregated_rating, r.review_cnt as review_count 
                FROM recipe r 
                JOIN follows f ON r.author_id = f.blogger_id 
                JOIN users u ON r.author_id = u.author_id 
                WHERE f.follower_id = ? 
                """);
        
        List<Object> args = new ArrayList<>();
        args.add(userId);

        if (category != null && !category.isEmpty()) {
            sqlBuilder.append(" AND r.category = ? ");
            args.add(category);
        }

        String countSql = "SELECT COUNT(*) FROM (" + sqlBuilder.toString() + ") as temp";
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, args.toArray());

        sqlBuilder.append(" ORDER BY r.date_published DESC, r.recipe_id DESC ");
        sqlBuilder.append(" LIMIT ? OFFSET ? ");
        args.add(size);
        args.add((page - 1) * size);

        List<FeedItem> items = jdbcTemplate.query(sqlBuilder.toString(), new BeanPropertyRowMapper<>(FeedItem.class), args.toArray());

        return PageResult.<FeedItem>builder()
                .items(items)
                .page(page)
                .size(size)
                .total(total == null ? 0 : total)
                .build();
    }

    @Override
    public Map<String, Object> getUserWithHighestFollowRatio() {
        String sql = """
                SELECT author_id, author_name, (CAST(followers AS FLOAT) / following) as ratio 
                FROM users 
                WHERE is_deleted = false AND following > 0 
                ORDER BY ratio DESC, author_id ASC 
                LIMIT 1
                """;
        try {
            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                Map<String, Object> map = new HashMap<>();
                map.put("AuthorId", rs.getLong("author_id"));
                map.put("AuthorName", rs.getString("author_name"));
                map.put("Ratio", rs.getDouble("ratio"));
                return map;
            });
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}
