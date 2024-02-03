package org.bot0ff.dto;

import lombok.Data;
import org.bot0ff.entity.Location;
import org.bot0ff.entity.Unit;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;

@Data
public class MainResponse {
    private Unit player;
    private Location location;
    private int ais;
    private int units;
    private int things;
    private String info;
    private int status;

    public MainResponse(Unit player, Location location, String info) {
        this.player = player;
        this.location = location;
        this.ais = location.getAis().size();
        location.getUnits().removeIf(u -> u.equals(player.getId()));
        this.units = location.getUnits().size();
        this.things = location.getThings().size();
        this.info = Objects.requireNonNullElseGet(info, () -> new SimpleDateFormat("dd-MM-yyyy HH:mm")
                .format(new Date()));
        this.status = 1;
    }
}
