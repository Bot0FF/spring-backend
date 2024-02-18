package org.bot0ff.service.fight;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bot0ff.entity.enums.UnitType;
import org.bot0ff.entity.unit.UnitFightStep;
import org.bot0ff.model.InfoResponse;
import org.bot0ff.model.FightResponse;
import org.bot0ff.model.NavigateResponse;
import org.bot0ff.entity.unit.UnitFightEffect;
import org.bot0ff.entity.*;
import org.bot0ff.entity.enums.ApplyType;
import org.bot0ff.entity.enums.Status;
import org.bot0ff.repository.AbilityRepository;
import org.bot0ff.repository.FightRepository;
import org.bot0ff.repository.UnitRepository;
import org.bot0ff.service.generate.EntityGenerator;
import org.bot0ff.util.Constants;
import org.bot0ff.util.JsonProcessor;
import org.bot0ff.util.RandomUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FightService {
    private final UnitRepository unitRepository;
    private final FightRepository fightRepository;
    private final AbilityRepository abilityRepository;

    private final PhysActionHandler physActionHandler;
    private final MagActionHandler magActionHandler;
    private final AiActionHandler aiActionHandler;
    private final EntityGenerator entityGenerator;
    private final JsonProcessor jsonProcessor;
    private final RandomUtil randomUtil;

    public static Map<Long, FightHandler> FIGHT_MAP = Collections.synchronizedMap(new HashMap<>());

    //начать сражение с выбранным unit
    @Transactional
    public String setStartFight(String name, Long initiatorId, Long targetId) {
        //определяем инициатора сражения и противника, в зависимости от того, кто напал
        Optional<Unit> optionalInitiator;
        if(name != null) {
            optionalInitiator = unitRepository.findByName(name);
        }
        else {
            optionalInitiator = unitRepository.findById(initiatorId);
        }

        if(optionalInitiator.isEmpty()) {
            var response = jsonProcessor
                    .toJsonInfo(new InfoResponse("Игрок не найден"));
            log.info("Не найден unit в БД по запросу name: {}", name);
            return response;
        }
        Unit initiator = optionalInitiator.get();

        //поиск opponent в бд
        var optionalOpponent = unitRepository.findById(targetId);
        if(optionalOpponent.isEmpty()) {
            resetUnitFight(initiator, Status.ACTIVE);
            var response = jsonProcessor
                    .toJsonInfo(new InfoResponse("Противник уже ушел"));
            log.info("Не найден opponent в БД по запросу targetId: {}", targetId);
            return response;
        }
        Unit opponent = optionalOpponent.get();

        //если противник уже в бою, проверяем есть ли место в бою, для участия
        if(opponent.getStatus().equals(Status.FIGHT)) {
            //ищем количество участников в противоположной противника команде
            List<Unit> initiatorTeam = opponent.getFight().getUnits().stream().filter(unit -> !unit.getTeamNumber().equals(opponent.getTeamNumber())).toList();
            if(initiatorTeam.size() >= Constants.MAX_COUNT_FIGHT_TEAM) {
                resetUnitFight(initiator, Status.ACTIVE);
                var response = jsonProcessor
                        .toJsonInfo(new InfoResponse("В сражении достаточно участников"));
                log.info("Нет места для сражения с противником - opponentId: {}", opponent.getId());
                return response;
            }
            //добавляем нападающего в противоположную команду в случайное место на поле сражения
            else {
                if(opponent.getTeamNumber().equals(1L)) {
                    setUnitFight(initiator, opponent.getFight(), 2L);
                }
                else {
                    setUnitFight(initiator, opponent.getFight(), 1L);
                }
                //отправляем ответ со статусом unit FIGHT
                return jsonProcessor
                        .toJsonFight(new FightResponse(initiator, opponent.getFight(), null, ""));
            }
        }

        //создание и сохранение нового сражения
        Fight newFight = getNewFight(initiator, opponent);
        Long newFightId = fightRepository.save(newFight).getId();

        //сохранение статуса FIGHT у player
        setUnitFight(initiator, newFight, 1L);

        //сохранение статуса FIGHT у enemy
        setUnitFight(opponent, newFight, 2L);

        //добавление нового сражения в map и запуск обработчика раундов
        FIGHT_MAP.put(newFightId, new FightHandler(
                newFightId,
                unitRepository,
                fightRepository,
                abilityRepository,
                aiActionHandler,
                entityGenerator,
                physActionHandler,
                magActionHandler
        ));

        //если инициатор сражения AI возвращаем null
        if(initiator.getUnitType().equals(UnitType.AI)) {
            return null;
        }

        return jsonProcessor
                .toJsonFight(new FightResponse(initiator, newFight, null, ""));
    }

    //текущее состояние сражения
    @Transactional
    public String getRefreshCurrentRound(String name) {
        var optionalPlayer = unitRepository.findByName(name);
        if(optionalPlayer.isEmpty()) {
            var response = jsonProcessor
                    .toJsonInfo(new InfoResponse("Игрок не найден"));
            log.info("Не найден player в БД по запросу username: {}", name);
            return response;
        }
        Unit player = optionalPlayer.get();

        //если сражение не найдено в БД, сбрасываем настройки сражения unit
        Optional<Fight> optionalFight = Optional.ofNullable(player.getFight());
        if(optionalFight.isEmpty()) {
            resetUnitFight(player, Status.ACTIVE);
            return jsonProcessor
                    .toJsonNavigate(new NavigateResponse());
        }
        Fight fight = optionalFight.get();

        //находим активные умения unit
        List<Ability> unitAbilities = abilityRepository.findAllById(player.getCurrentAbility());

        //если сражение завершено или unit не в команде устанавливаем unit статус победителя или проигравшего и возвращаем уведомление
        if(fight.isFightEnd() | player.getTeamNumber() == null) {
            if(fight.getUnitsWin().stream().anyMatch(unit -> unit.equals(player.getId()))) {
                resetUnitFight(player, Status.WIN);
            }
            else if(fight.getUnitsLoss().stream().anyMatch(unit -> unit.equals(player.getId()))) {
                resetUnitFight(player, Status.LOSS);
            }
            else {
                resetUnitFight(player, Status.ACTIVE);
            }
            return jsonProcessor
                    .toJsonNavigate(new NavigateResponse());
        }

        return jsonProcessor
                .toJsonFight(new FightResponse(player, fight, unitAbilities, null));
    }

    //перемещение по полю сражения
    @Transactional
    public String moveOnFightFiled(String name, String direction) {
        var optionalPlayer = unitRepository.findByName(name);
        if(optionalPlayer.isEmpty()) {
            var response = jsonProcessor
                    .toJsonInfo(new InfoResponse("Игрок не найден"));
            log.info("Не найден player в БД по запросу username: {}", name);
            return response;
        }
        Unit player = optionalPlayer.get();

        //если сражение не найдено в БД, сбрасываем настройки сражения unit
        Optional<Fight> optionalFight = Optional.ofNullable(player.getFight());
        if(optionalFight.isEmpty()) {
            resetUnitFight(player, Status.ACTIVE);
            var response = jsonProcessor
                    .toJsonNavigate(new NavigateResponse());
            log.info("Не найдено сражение в БД по запросу player: {}", player.getName());
            return response;
        }
        Fight fight = optionalFight.get();

        //находим активные умения unit
        List<Ability> unitAbilities = abilityRepository.findAllById(player.getCurrentAbility());

        //если очков движения нет, отправляем уведомление
        if(player.getPointAction() <= 0) {
            return jsonProcessor
                    .toJsonFight(new FightResponse(player, fight, unitAbilities, "Не хватает очков действия"));
        }

        //перемещение по полю сражения
        String moveDirection = "";
        switch (direction) {
            case "left" -> {
                if(player.getLinePosition() > 0) {
                    player.setPointAction(player.getPointAction() - 1);
                    player.setLinePosition(player.getLinePosition() - 1);
                    if(player.getPointAction() < 1) {
                        player.setActionEnd(true);
                    }
                    unitRepository.save(player);
                    moveDirection = " влево";
                }
                else {
                    return jsonProcessor
                            .toJsonFight(new FightResponse(player, fight, unitAbilities, "Туда нельзя переместиться"));
                }
            }
            case "right" -> {
                if(player.getLinePosition() < Constants.FIGHT_LINE_LENGTH) {
                    player.setPointAction(player.getPointAction() - 1);
                    player.setLinePosition(player.getLinePosition() + 1);
                    if(player.getPointAction() < 1) {
                        player.setActionEnd(true);
                    }
                    unitRepository.save(player);
                    moveDirection = " вправо";
                }
                else {
                    return jsonProcessor
                            .toJsonFight(new FightResponse(player, fight, unitAbilities, "Туда нельзя переместиться"));
                }
            }
        }

        //если все участники сражения к этому времени сделали ходы, завершаем раунд
        if(fight.getUnits().stream().allMatch(Unit::isActionEnd)) {
            FIGHT_MAP.get(player.getFight().getId()).setEndRoundTimer(Instant.now());
        }

        return jsonProcessor
                .toJsonFight(new FightResponse(player, fight, unitAbilities, player.getName() + " переместился " + moveDirection));
    }

    //атака по выбранному противнику оружием
    @Transactional
    public String setApplyWeapon(String name, Long targetId) {
        var optionalPlayer = unitRepository.findByName(name);
        if(optionalPlayer.isEmpty()) {
            var response = jsonProcessor
                    .toJsonInfo(new InfoResponse("Игрок не найден"));
            log.info("Не найден player в БД по запросу username: {}", name);
            return response;
        }
        Unit player = optionalPlayer.get();

        //если сражение не найдено, сбрасываем настройки сражения unit
        Optional<Fight> optionalFight = Optional.ofNullable(player.getFight());
        if(optionalFight.isEmpty()) {
            resetUnitFight(player, Status.ACTIVE);
            var response = jsonProcessor
                    .toJsonNavigate(new NavigateResponse());
            log.info("Не найдено сражение в БД по запросу обновления состояния от player: {}", player.getName());
            return response;
        }
        Fight fight = optionalFight.get();

        //проверка наличия противника
        Optional<Unit> optionalTarget = fight.getUnits().stream().filter(unit -> unit.getId().equals(targetId)).findFirst();
        if(optionalTarget.isEmpty()) {
            resetUnitFight(player, Status.ACTIVE);
            log.info("Не найден противник в БД при атаке оружием - targetId: {}", targetId);
            return jsonProcessor
                    .toJson(new NavigateResponse());
        }
        Unit target = optionalTarget.get();

        //находим активные умения unit для возврата в ответе
        List<Ability> unitAbilities = abilityRepository.findAllById(player.getCurrentAbility());

        //если ход уже сделан, возвращаем уведомление с текущим состоянием сражения
        if(player.isActionEnd()) {
            return jsonProcessor
                    .toJsonInfo(new InfoResponse("Ход уже сделан"));
        }

        //проверка достаточности очков действия для нанесения удара
        if(player.getPointAction() < Constants.POINT_ACTION_WEAPON) {
            return jsonProcessor
                    .toJsonInfo(new InfoResponse("Не хватает очков действия"));
        }

        //уведомление при попытке атаковать союзника
        if(player.getTeamNumber().equals(target.getTeamNumber())) {
            return jsonProcessor
                    .toJsonInfo(new InfoResponse("Нельзя атаковать союзников"));
        }

        //сохранение умения и цели, по которой произведено действие
        player.getFightStep().add(new UnitFightStep(
                0L, targetId, player.getLinePosition(), target.getLinePosition()
        ));
        player.setPointAction(player.getPointAction() - Constants.POINT_ACTION_WEAPON);
        if(player.getPointAction() < 1) {
            player.setActionEnd(true);
        }
        unitRepository.save(player);

        //если все участники сражения к этому времени сделали ходы, завершаем раунд
        if(fight.getUnits().stream().allMatch(Unit::isActionEnd)) {
            FIGHT_MAP.get(player.getFight().getId()).setEndRoundTimer(Instant.now());
        }

        //проверяем надето ли оружие у unit
        String playerWeapon = "оружием " + player.getWeapon().getName();
        if (player.getWeapon().getId() == 0L) {
            playerWeapon = "голыми кулаками";
        }
        return jsonProcessor
                .toJsonFight(new FightResponse(player, fight, unitAbilities, "Атака " + playerWeapon));
    }

    //атака по выбранному противнику умением
    @Transactional
    public String setApplyAbility(String name, Long abilityId, Long targetId) {
        var optionalPlayer = unitRepository.findByName(name);
        if(optionalPlayer.isEmpty()) {
            var response = jsonProcessor
                    .toJsonInfo(new InfoResponse("Игрок не найден"));
            log.info("Не найден player в БД по запросу username: {}", name);
            return response;
        }
        Unit player = optionalPlayer.get();

        //если сражение не найдено сбрасываем настройки сражения unit, не меняя статус
        Optional<Fight> optionalFight = Optional.ofNullable(player.getFight());
        if(optionalFight.isEmpty()) {
            resetUnitFight(player, Status.ACTIVE);
            var response = jsonProcessor
                    .toJsonNavigate(new NavigateResponse());
            log.info("Не найдено сражение в БД по запросу обновления состояния от player: {}", player.getName());
            return response;
        }
        Fight fight = optionalFight.get();

        //находим примененное умение из бд
        Optional<Ability> optionalAbility = abilityRepository.findById(abilityId);
        if(optionalAbility.isEmpty()) {
            return jsonProcessor
                    .toJsonInfo(new InfoResponse("Умение не найдено"));
        }
        Ability ability = optionalAbility.get();

        //если недостаточно маны для применения умения, возвращаем уведомление
        if(player.getMana() < ability.getManaCost()) {
            return jsonProcessor
                    .toJsonInfo(new InfoResponse("Не хватает маны для этого умения"));
        }

        //проверка наличия противника
        Optional<Unit> optionalTarget = fight.getUnits().stream().filter(unit -> unit.getId().equals(targetId)).findFirst();
        if(optionalTarget.isEmpty()) {
            resetUnitFight(player, Status.ACTIVE);
            log.info("Не найден противник в БД при атаке оружием - targetId: {}", targetId);
            return jsonProcessor
                    .toJsonNavigate(new NavigateResponse());
        }
        Unit target = optionalTarget.get();

        //если ход уже сделан, возвращаем уведомление с текущим состоянием сражения
        if(player.isActionEnd()) {
            return jsonProcessor
                    .toJsonInfo(new InfoResponse("Ход уже сделан"));
        }

        //проверка достаточности очков действия для применения умения
        if(player.getPointAction() < ability.getPointAction()) {
            return jsonProcessor
                    .toJsonInfo(new InfoResponse("Не хватает очков действия"));
        }

        //уведомление при попытке использовать восстанавливающие умения на противниках
        if((ability.getApplyType().equals(ApplyType.RECOVERY)
                | ability.getApplyType().equals(ApplyType.BOOST))
                & !player.getTeamNumber().equals(target.getTeamNumber())) {
            return jsonProcessor
                    .toJsonInfo(new InfoResponse("Это умение для союзников"));
        }

        //уведомление при попытке использовать понижающие или атакующие умения на союзниках
        if((ability.getApplyType().equals(ApplyType.DAMAGE)
                | ability.getApplyType().equals(ApplyType.LOWER))
                & player.getTeamNumber().equals(target.getTeamNumber())) {
            return jsonProcessor
                    .toJsonInfo(new InfoResponse("Это умение для противников"));
        }

        //если на цели уже применено данное умение, возвращаем уведомление
        if(ability.getApplyType().equals(ApplyType.BOOST)
                | ability.getApplyType().equals(ApplyType.LOWER)) {
            boolean isApplied = false;
            if(ability.getHp() != 0 & target.getFightEffect().getDE_Hp() != 0) {
                isApplied = true;
            }
            else if(ability.getMana() != 0 & target.getFightEffect().getDE_Mana() != 0) {
                isApplied = true;
            }
            else if(ability.getPhysEffect() != 0 & target.getFightEffect().getDE_PhysEff() != 0) {
                isApplied = true;
            }
            else if(ability.getMagEffect() != 0 & target.getFightEffect().getDE_MagEff() != 0) {
                isApplied = true;
            }
            else if(ability.getPhysDefense() != 0 & target.getFightEffect().getDE_PhysDef() != 0) {
                isApplied = true;
            }
            else if(ability.getMagDefense() != 0 & target.getFightEffect().getDE_MagDef() != 0) {
                isApplied = true;
            }
            else if(ability.getStrength() != 0 & target.getFightEffect().getDE_Str() != 0) {
                isApplied = true;
            }
            else if(ability.getIntelligence() != 0 & target.getFightEffect().getDE_Intel() != 0) {
                isApplied = true;
            }
            else if(ability.getDexterity() != 0 & target.getFightEffect().getDE_Dext() != 0) {
                isApplied = true;
            }
            else if(ability.getEndurance() != 0 & target.getFightEffect().getDE_Endur() != 0) {
                isApplied = true;
            }
            else if(ability.getLuck() != 0 & target.getFightEffect().getDE_Luck() != 0) {
                isApplied = true;
            }
            else if(ability.getInitiative() != 0 & target.getFightEffect().getDE_Init() != 0) {
                isApplied = true;
            }
            else if(ability.getBlock() != 0 & target.getFightEffect().getDE_Block() != 0) {
                isApplied = true;
            }
            else if(ability.getEvade() != 0 & target.getFightEffect().getDE_Evade() != 0) {
                isApplied = true;
            }
            if(isApplied) {
                return jsonProcessor
                        .toJsonInfo(new InfoResponse("Умение уже применено"));
            }
        }

        //находим активные умения unit для отправки в ответе
        List<Ability> unitAbilities = abilityRepository.findAllById(player.getCurrentAbility());

        //сохранение умения и цели, по которой произведено действие
        player.getFightStep().add(new UnitFightStep(
                abilityId, targetId, player.getLinePosition(), target.getLinePosition()
        ));
        player.setMana(player.getMana() - ability.getManaCost());
        player.setPointAction(player.getPointAction() - ability.getPointAction());
        if(player.getPointAction() < 1) {
            player.setActionEnd(true);
        }
        unitRepository.save(player);

        //если все участники сражения к этому времени сделали ходы, завершаем раунд
        if(fight.getUnits().stream().allMatch(Unit::isActionEnd)) {
            FIGHT_MAP.get(player.getFight().getId()).setEndRoundTimer(Instant.now());
        }

        return jsonProcessor
                .toJsonFight(new FightResponse(player, fight, unitAbilities, player.getName() + " применил умение " + ability.getName()));
    }

    //TODO сделать обработчик для массовых умений

    //завершает ход unit
    public String setActionEnd(String name) {
        //поиск player в бд
        var optionalPlayer = unitRepository.findByName(name);
        if(optionalPlayer.isEmpty()) {
            var response = jsonProcessor
                    .toJsonNavigate(new NavigateResponse());
            log.info("Не найден unit в БД по запросу username: {}", name);
            return response;
        }
        Unit player = optionalPlayer.get();

        //если сражение не найдено сбрасываем настройки сражения unit, не меняя статус
        Optional<Fight> optionalFight = Optional.ofNullable(player.getFight());
        if(optionalFight.isEmpty()) {
            resetUnitFight(player, Status.ACTIVE);
            var response = jsonProcessor
                    .toJsonNavigate(new NavigateResponse());
            log.info("Не найдено сражение в БД по запросу обновления состояния от player: {}", player.getName());
            return response;
        }
        Fight fight = optionalFight.get();

        //находим активные умения unit для отправки в ответе
        List<Ability> unitAbilities = abilityRepository.findAllById(player.getCurrentAbility());

        player.setPointAction(0);
        player.setActionEnd(true);
        unitRepository.save(player);

        //если все участники сражения к этому времени сделали ходы, завершаем раунд
        if(fight.getUnits().stream().allMatch(Unit::isActionEnd)) {
            FIGHT_MAP.get(player.getFight().getId()).setEndRoundTimer(Instant.now().plusSeconds(2));
        }

        return jsonProcessor
                .toJsonFight(new FightResponse(player, fight, unitAbilities, "Ход завершен"));
    }

    //TODO сделать метод для массовых умений

    //создание нового сражения
    private Fight getNewFight(Unit initiator, Unit opponent) {
        return new Fight(null,
                new ArrayList<>(List.of(initiator, opponent)),
                1, new ArrayList<>(List.of("")), false, new ArrayList<>(), new ArrayList<>());
    }

    //сохранение настроек нового сражения unit
    private void setUnitFight(Unit unit, Fight newFight, Long teamNumber) {
        unit.setActionEnd(false);
        unit.setStatus(Status.FIGHT);
        unit.setFight(newFight);
        unit.setTeamNumber(teamNumber);
        unit.setFightStep(List.of());
        unit.setPointAction(unit.getMaxPointAction());
        unit.setLinePosition((long) randomUtil.getRandomFromTo(0, Constants.FIGHT_LINE_LENGTH));
        unit.setFightEffect(new UnitFightEffect());
        unitRepository.save(unit);
    }

    //сброс настроек сражения unit при ошибке
    private void resetUnitFight(Unit unit, Status status) {
        unit.setStatus(status);
        unit.setActionEnd(false);
        unit.setFight(null);
        unit.setFightStep(null);
        unit.setLinePosition(null);
        unit.setFightEffect(null);
        unit.setTeamNumber(null);
        unitRepository.save(unit);
    }
}
