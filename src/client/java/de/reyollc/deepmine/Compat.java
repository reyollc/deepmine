package de.reyollc.deepmine;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Die eine Stelle, an der sich 26.2 und 26.3 unterscheiden.
 *
 * 26.2:  LivingEntity.swing(InteractionHand)
 * 26.3:  LivingEntity.swing(InteractionHand, SwingAnimation, boolean)
 *        plus ItemStack.getAttackAnimation() / getInteractAnimation()
 *
 * Beide direkt aufzurufen ginge nicht: die 26.3-Variante braucht die Klasse
 * SwingAnimation, die es in 26.2 nicht gibt, und ein Jar, das sie im Bytecode
 * erwaehnt, laedt dort nicht. Deshalb wird hier einmal beim Laden nachgesehen,
 * welche Signatur vorhanden ist. Alles Uebrige im Mod ist in beiden Versionen
 * identisch - das ist gegen beide Minecraft-Jars gegengeprueft.
 */
final class Compat {

    private static final Logger LOG = LoggerFactory.getLogger("deepmine");

    /** 26.3: swing(hand, animation, boolean) */
    private static final Method SWING_NEW;
    /** 26.2: swing(hand) */
    private static final Method SWING_OLD;
    private static final Method GET_ATTACK_ANIMATION;
    private static final Method GET_INTERACT_ANIMATION;

    static {
        Method swingNew = null, swingOld = null, attackAnim = null, interactAnim = null;
        try {
            Class<?> anim = Class.forName("net.minecraft.world.item.component.SwingAnimation");
            swingNew = LocalPlayer.class.getMethod("swing", InteractionHand.class, anim, boolean.class);
            attackAnim = ItemStack.class.getMethod("getAttackAnimation");
            interactAnim = ItemStack.class.getMethod("getInteractAnimation");
        } catch (ReflectiveOperationException ignored) {
            // Kein SwingAnimation: dann ist es 26.2.
        }
        if (swingNew == null) {
            try {
                swingOld = LocalPlayer.class.getMethod("swing", InteractionHand.class);
            } catch (ReflectiveOperationException e) {
                LOG.error("DeepMine: keine bekannte swing-Methode gefunden, Armbewegung bleibt aus", e);
            }
        }
        SWING_NEW = swingNew;
        SWING_OLD = swingOld;
        GET_ATTACK_ANIMATION = attackAnim;
        GET_INTERACT_ANIMATION = interactAnim;
    }

    private Compat() {}

    /** Armbewegung beim Abbauen und Zuschlagen. */
    static void swingAttack(LocalPlayer p) {
        swing(p, GET_ATTACK_ANIMATION);
    }

    /** Armbewegung beim Benutzen: Fackel setzen, Shulker setzen und oeffnen. */
    static void swingUse(LocalPlayer p) {
        swing(p, GET_INTERACT_ANIMATION);
    }

    private static void swing(LocalPlayer p, Method animationGetter) {
        try {
            if (SWING_NEW != null) {
                Object animation = animationGetter.invoke(p.getMainHandItem());
                SWING_NEW.invoke(p, InteractionHand.MAIN_HAND, animation, false);
            } else if (SWING_OLD != null) {
                SWING_OLD.invoke(p, InteractionHand.MAIN_HAND);
            }
        } catch (ReflectiveOperationException e) {
            // Die Armbewegung ist reine Optik; der Abbau laeuft ueber gameMode
            // und funktioniert auch ohne sie. Deshalb nur melden, nicht abbrechen.
            LOG.warn("DeepMine: swing fehlgeschlagen: {}", e.toString());
        }
    }
}
