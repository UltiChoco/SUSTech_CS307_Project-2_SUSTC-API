package io.sustc.controller;

import io.sustc.dto.AuthInfo;
import io.sustc.dto.FeedItem;
import io.sustc.dto.PageResult;
import io.sustc.dto.RegisterUserReq;
import io.sustc.dto.UserRecord;
import io.sustc.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserService userService;

    @PostMapping("/register")
    public long register(@RequestBody RegisterUserReq req) {
        return userService.register(req);
    }

    @PostMapping("/login")
    public long login(@RequestBody AuthInfo auth) {
        return userService.login(auth);
    }

    @DeleteMapping
    public boolean deleteAccount(@RequestHeader("Auth-Id") long authId, 
                                 @RequestHeader("Auth-Password") String password, 
                                 @RequestParam long userId) {
        return userService.deleteAccount(createAuth(authId, password), userId);
    }

    @PostMapping("/follow/{followeeId}")
    public boolean follow(@RequestHeader("Auth-Id") long authId, 
                          @RequestHeader("Auth-Password") String password, 
                          @PathVariable long followeeId) {
        return userService.follow(createAuth(authId, password), followeeId);
    }

    @GetMapping("/{userId}")
    public UserRecord getById(@PathVariable long userId) {
        return userService.getById(userId);
    }

    @PutMapping("/profile")
    public void updateProfile(@RequestHeader("Auth-Id") long authId, 
                              @RequestHeader("Auth-Password") String password, 
                              @RequestParam(required = false) String gender, 
                              @RequestParam(required = false) Integer age) {
        userService.updateProfile(createAuth(authId, password), gender, age);
    }

    @GetMapping("/feed")
    public PageResult<FeedItem> feed(@RequestHeader("Auth-Id") long authId, 
                                     @RequestHeader("Auth-Password") String password, 
                                     @RequestParam(defaultValue = "1") int page, 
                                     @RequestParam(defaultValue = "10") int size, 
                                     @RequestParam(required = false) String category) {
        return userService.feed(createAuth(authId, password), page, size, category);
    }

    @GetMapping("/analytics/ratio")
    public Map<String, Object> getUserWithHighestFollowRatio() {
        return userService.getUserWithHighestFollowRatio();
    }

    private AuthInfo createAuth(long id, String password) {
        return AuthInfo.builder().authorId(id).password(password).build();
    }
}
