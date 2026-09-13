package com.pinhaoche.place;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 小程序端地点接口（openapi: GET /places）。 */
@RestController
@RequestMapping("/api/v1")
public class PlaceController {

    private final PlaceService placeService;

    public PlaceController(PlaceService placeService) {
        this.placeService = placeService;
    }

    @GetMapping("/places")
    public List<PlaceService.PlaceDto> list() {
        return placeService.listEnabled();
    }
}
