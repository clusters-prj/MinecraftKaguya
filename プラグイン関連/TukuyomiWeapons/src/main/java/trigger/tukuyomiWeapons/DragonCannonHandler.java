package trigger.tukuyomiWeapons;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 竜撃砲(ウルト): 前方10ブロック・後方2ブロックに、それぞれ60度の円錐状に広がる爆煙を放ち、範囲内の相手に70ダメージ。
 * 角度は円錐全体の開き(斬撃と同じ数え方)。壁の向こうには届かない。
 */
public final class DragonCannonHandler implements WeaponHandler {
    static final double FRONT_RANGE = 10;
    static final double BACK_RANGE = 2;
    static final double ANGLE = 60;
    static final double DAMAGE = 70;

    @Override public void onRightClick(TukuyomiWeapons plugin, PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!plugin.ult.consumeCharge(p)) return;

        Location eye = p.getEyeLocation();
        Vector front = eye.getDirection().normalize();
        Vector back = front.clone().multiply(-1);
        World world = p.getWorld();
        world.playSound(eye, Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.7f);
        world.playSound(eye, Sound.ENTITY_BLAZE_SHOOT, 2f, 0.5f);
        ConeUtil.smoke(world, eye, front, FRONT_RANGE, ANGLE);
        ConeUtil.smoke(world, eye, back, BACK_RANGE, ANGLE);

        Set<UUID> done = new HashSet<>();
        for (LivingEntity le : ConeUtil.inCone(plugin, eye, front, FRONT_RANGE, ANGLE, p)) {
            if (done.add(le.getUniqueId())) plugin.damageBy(le, DAMAGE, p);
        }
        for (LivingEntity le : ConeUtil.inCone(plugin, eye, back, BACK_RANGE, ANGLE, p)) {
            if (done.add(le.getUniqueId())) plugin.damageBy(le, DAMAGE, p);
        }
    }
}
