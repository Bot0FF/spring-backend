package org.bot0ff.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bot0ff.entity.Enemy;
import org.bot0ff.entity.Library;
import org.bot0ff.entity.Status;
import org.bot0ff.repository.EnemyRepository;
import org.bot0ff.repository.LibraryRepository;
import org.bot0ff.repository.LocationRepository;
import org.bot0ff.repository.PlayerRepository;
import org.bot0ff.util.Constants;
import org.bot0ff.util.JsonProcessor;
import org.bot0ff.dto.response.MainBuilder;
import org.bot0ff.util.RandomUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MainService {
    private final PlayerRepository playerRepository;
    private final EnemyRepository enemyRepository;
    private final LibraryRepository libraryRepository;
    private final LocationRepository locationRepository;
    private final JsonProcessor jsonProcessor;
    private final RandomUtil randomUtil;

    //состояние user после обновления страницы
    public String getPlayerState(String username) {
        var player = playerRepository.findByName(username);
        if(player.isEmpty()) {
            var response = MainBuilder.builder()
                    .status(HttpStatus.NO_CONTENT)
                    .build();
            log.info("Не найден player в БД по запросу username: {}", username);
            return jsonProcessor.toJsonMainResponse(response);
        }
        var location = locationRepository.findById(player.get().getLocationId());
        if(location.isEmpty()) {
            var response = MainBuilder.builder()
                    .player(player.get())
                    .status(HttpStatus.NO_CONTENT)
                    .build();
            log.info("Не найдена location в БД по запросу locationId: {}", player.get().getLocationId());
            return jsonProcessor.toJsonMainResponse(response);
        }
        var response = MainBuilder.builder()
                .player(player.get())
                .enemies(location.get().getEnemies())
                .players(location.get().getPlayers())
                .content(location.get().getName())
                .status(HttpStatus.OK)
                .build();

        return jsonProcessor.toJsonMainResponse(response);
    }

    //смена локации user
    @Transactional
    public String movePlayer(String username, String direction) {
        var player = playerRepository.findByName(username).orElse(null);
        if(player == null) {
            var response = MainBuilder.builder()
                    .status(HttpStatus.NO_CONTENT)
                    .build();
            log.info("Не найден player в БД по запросу username: {}", username);
            return jsonProcessor.toJsonMainResponse(response);
        }
        switch (direction) {
            case "up" -> {
                if(player.getY() + 1 <= Constants.MAX_MAP_LENGTH) {
                    player.setY(player.getY() + 1);
                }
            }
            case "left" -> {
                if(player.getX() - 1 > 0) {
                    player.setX(player.getX() - 1);
                }
            }
            case "right" -> {
                if(player.getX() + 1 <= Constants.MAX_MAP_LENGTH) {
                     player.setX(player.getX() + 1);
                }
            }
            case "down" -> {
                if(player.getY() - 1 > 0) {
                     player.setY(player.getY() - 1);
                }
            }
        }
        var newLocationId = Long.parseLong("" + player.getX() + player.getY());
        playerRepository.saveNewPlayerPosition(player.getX(), player.getY(), newLocationId, player.getName());

        var location = locationRepository.findById(newLocationId);
        if(location.isEmpty()) {
            var response = MainBuilder.builder()
                    .player(player)
                    .status(HttpStatus.NO_CONTENT)
                    .build();
            log.info("Не найдена newLocation в БД по запросу newLocationId: {}", newLocationId);
            return jsonProcessor.toJsonMainResponse(response);
        }

        //шанс появления enemy на локации
        //TODO добавить в library location
        if(randomUtil.getChanceCreateEnemy()) {
            Optional<Library> enemyLibrary = libraryRepository.findById(randomUtil.getRandomEnemyId());
            if(enemyLibrary.isPresent()) {
                Enemy newEnemy = new Enemy(randomUtil.getRandomId(), player.getX(), player.getY(), player.getLocation().getLocationType(),
                        location.get(), enemyLibrary.get().getName(), Status.ACTIVE, null, enemyLibrary.get().getHp(), enemyLibrary.get().getDamage(),
                        false, 0, null);
                enemyRepository.save(newEnemy);
            }
        }

        var response = MainBuilder.builder()
                .player(player)
                .enemies(location.get().getEnemies())
                .players(location.get().getPlayers())
                .content(location.get().getName())
                .status(HttpStatus.OK)
                .build();
        return jsonProcessor.toJsonMainResponse(response);
    }
}
