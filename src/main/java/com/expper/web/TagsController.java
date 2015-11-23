package com.expper.web;

import com.codahale.metrics.annotation.Timed;
import com.expper.domain.Post;
import com.expper.domain.Tag;
import com.expper.domain.Vote;
import com.expper.repository.PostRepository;
import com.expper.repository.TagRepository;
import com.expper.security.SecurityUtils;
import com.expper.service.CountingService;
import com.expper.service.HotPostService;
import com.expper.service.NewPostsService;
import com.expper.service.PostService;
import com.expper.service.TagService;
import com.expper.service.UserService;
import com.expper.service.VoteService;
import com.expper.web.exceptions.PageNotFoundException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;

import static org.springframework.web.bind.annotation.RequestMethod.GET;

/**
 * @author Raysmond<i@raysmond.com>
 */
@Controller
public class TagsController {
    @Autowired
    private TagService tagService;

    @Autowired
    private HotPostService hotPostService;

    @Autowired
    private NewPostsService newPostsService;

    @Autowired
    private VoteService voteService;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private CountingService countingService;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private UserService userService;

    private static final int PAGE_SIZE = 20;

    /**
     * 热门标签，搜素标签
     */
    @RequestMapping(value = "/tags", method = GET)
    @Timed
    public String index(Model model, @RequestParam(defaultValue = "1") int page, @RequestParam(required = false) String search) {
        long pages;
        page = page < 1 ? 1 : page - 1;

        Page<Tag> tags;

        if (search == null || search.trim().isEmpty()) {
            tags = tagService.getHotTags(page, PAGE_SIZE);
            long size = tagService.countTags();
            pages = size / PAGE_SIZE + (size % PAGE_SIZE != 0 ? 1 : 0);
        } else {
            // Do search tags
            tags = tagRepository.searchTags(
                search.toUpperCase(),
                new PageRequest(page, PAGE_SIZE, Sort.Direction.DESC, "postCount", "followersCount"));

            pages = tags.getTotalPages();
        }

        model.addAttribute("search", search == null ? "" : search.trim());
        model.addAttribute("tags", tags);
        model.addAttribute("page", page + 1);
        model.addAttribute("totalPages", pages);

        // Check whether the current user already followed the tags or not
        Map<Long, Boolean> followed = new HashMap<>();
        if (SecurityUtils.isAuthenticated()) {
            List<Long> tagIds = new ArrayList<>();
            tags.forEach(tag -> tagIds.add(tag.getId()));
            followed = tagService.isUserFollowedTags(tagIds, userService.getCurrentUserId());
        }
        model.addAttribute("followed", followed);

        return "tags/index";
    }

    /**
     * 我的关注标签
     */
    @RequestMapping(value = "/me/tags/following", method = GET)
    @Timed
    public String followed(Model model, @RequestParam(defaultValue = "1") int page) {
        Long userId = userService.getCurrentUserId();
        long size = tagService.countUserFollowedTags(userId);
        long pages = size / PAGE_SIZE + (size % PAGE_SIZE != 0 ? 1 : 0);
        page = page < 1 ? 1 : page - 1;

        List<Tag> tags = tagService.getUserTags(page, PAGE_SIZE, userId);

        model.addAttribute("tags", tags);
        model.addAttribute("page", page + 1);
        model.addAttribute("totalPages", pages);

        return "tags/followed_tags";
    }

    /**
     * 标签页面
     */
    @RequestMapping(value = "/tags/{tagName}", method = GET)
    @Timed
    public String tag(Model model, @PathVariable String tagName, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "hot") String sort) {
        if (!sort.equals("new")) {
            sort = "hot";
        }

        Tag tag = tagService.findByName(tagName);

        if (tag == null) {
            throw new PageNotFoundException("Tag " + tagName + " is not found.");
        }

        page = page < 1 ? 1 : page - 1;

        long size = 0;
        long pages = 0;

        Collection<Long> ids = new ArrayList<>();
        List<Post> posts = new ArrayList<>();

        if (sort.equals("hot")) {
            size = hotPostService.sizeOfTag(tag);

            Set<ZSetOperations.TypedTuple<Long>> idsWithScore = hotPostService.getPageWithScoreOfTag(tag.getId(), page, PAGE_SIZE);
            if (!idsWithScore.isEmpty()) {
                for (ZSetOperations.TypedTuple<Long> tuple : idsWithScore) {
                    ids.add(tuple.getValue());
                }

                posts = postRepository.findByIdIn(ids);
                hotPostService.sortByScore(posts, idsWithScore);
            }
        }

        if (sort.equals("new")) {
            size = newPostsService.sizeOfTagList(tag.getId());
            ids = newPostsService.getPageOfTag(tag.getId(), page, PAGE_SIZE);

            if (!ids.isEmpty()) {
                posts = postRepository.findByIdIn(ids);
            }
        }

        pages = size / PAGE_SIZE + (size % PAGE_SIZE != 0 ? 1 : 0);

        model.addAttribute("sort", sort);
        model.addAttribute("tag", tag);
        model.addAttribute("counting", countingService.getPostListCounting(ids));
        model.addAttribute("page", page + 1);
        model.addAttribute("totalPages", pages);
        model.addAttribute("posts", posts);

        Map<Long, Vote> userVotesMap = new HashMap<>();
        boolean isFollowed = false;
        if (SecurityUtils.isAuthenticated()) {
            Long userId = userService.getCurrentUserId();
            userVotesMap = voteService.getUserVoteMapFor(posts, userId);
            isFollowed = tagRepository.getUserFollowedTag(userId, tag.getId()) != null; // Check if the user followed the tag
        }
        model.addAttribute("votes", userVotesMap);
        model.addAttribute("isFollowed", isFollowed);

        return "tags/show";
    }
}
