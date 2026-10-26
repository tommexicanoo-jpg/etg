package com.theecho.client;

import com.theecho.ModBlocks;
import com.theecho.ModEntities;
import com.theecho.net.DreadPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.Random;

public class TheEchoClient implements ClientModInitializer {

    private static final Random R = new Random();
    private static final String[] WHISPERS = {"NO MIRES", "ATRÁS", "TE VEO", "SIGO AQUÍ", "NO ESTÁS SOLO", "CORRE", "MÍRAME"};

    private static final Text DAY_CAP = Text.literal("D Í A");
    private static final Text TIMER_CAP = Text.literal("EL MUNDO TE OLVIDA");
    private static int timerMax = 0;
    private static final Text BAR_LABEL = Text.literal("PARANOIA");
    private static Text dayText = Text.literal("DÍA 1");
    private static int dayTextFor = 1;
    private static int timer = -1; // segundos del apocalipsis (-1 = oculto)
    private static boolean hud = false;
    private static int targetDread = 0, targetPar = 0, dayShown = 1, stage = 0;
    private static float shown = 0f, shownPar = 0f, sepia = 0f;
    private static int flash = 0, flashMax = 1, flashKind = 1;
    private static int heartbeat = 0, breath = 0;
    private static int eyes = 0;
    private static float eyeX = 0.5f, eyeY = 0.4f;
    private static int whisper = 0, whisperIdx = 0;
    private static float whisperX = 0.1f, whisperY = 0.1f;
    private static int glitch = 0;
    private static int blackout = 0;

    // Título de bienvenida: "Día 1 de ▒▒▒▒" con letras que cambian sin parar
    private static final int INTRO_LEN = 170;
    private static final String GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789#%&@$?!/<>[]{}=+*~^";
    private static int introTicks = 0;
    private static String introScramble = "?????????";

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(ModEntities.ECHO, EchoEntityRenderer::new);
        // El espejo tiene transparencia: sin esto se ve con fondo negro
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ECHO_MIRROR, RenderLayer.getTranslucent());

        ClientPlayNetworking.registerGlobalReceiver(com.theecho.net.TimerPayload.ID, (payload, context) -> timer = payload.seconds());
        ClientPlayNetworking.registerGlobalReceiver(DreadPayload.ID, (payload, context) -> {
            if (payload.hud() >= 0) hud = payload.hud() == 1;
            if (payload.dread() >= 0) targetDread = Math.min(100, payload.dread());
            if (payload.paranoia() >= 0) targetPar = Math.min(100, payload.paranoia());
            if (payload.day() >= 0) dayShown = Math.max(1, payload.day());
            if (payload.stage() >= 0) stage = payload.stage();
            if (payload.scare() == 3) {
                introTicks = INTRO_LEN;
                var mcc = MinecraftClient.getInstance();
                mcc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_BELL_USE, 0.6f, 1.0f));
            } else if (payload.scare() > 0) {
                flashKind = payload.scare();
                flashMax = payload.scare() >= 2 ? 34 : 14;
                flash = flashMax;
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientPlayerEntity pl = client.player;
            if (pl == null) {
                timer = -1; hud = false; targetDread = 0; targetPar = 0; shown = 0f; shownPar = 0f; flash = 0; stage = 0; sepia = 0f;
                return;
            }
            if (client.isPaused()) return;
            if (introTicks > 0) {
                introTicks--;
                if (introTicks % 2 == 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 9; i++) sb.append(GLYPHS.charAt(R.nextInt(GLYPHS.length())));
                    introScramble = sb.toString();
                }
            }
            if (stage >= 3) {
                if (blackout > 0) blackout--;
                else if (R.nextInt(110) == 0) blackout = 2;
            }
            shown += (targetDread - shown) * 0.04f;
            shownPar += (targetPar - shownPar) * 0.04f;
            float sepiaTarget = stage >= 3 ? 0.3f : stage == 2 ? 0.33f : stage == 1 ? 0.07f : 0f;
            sepia += (sepiaTarget - sepia) * 0.01f;
            if (flash > 0) flash--;

            float intensity = Math.max(shown, shownPar * 0.8f);

            // La cámara tiembla cuando el miedo es alto
            if (shown > 30f && R.nextInt(1000) < shown / 3f) {
                float pitch = pl.getPitch() + (R.nextFloat() - 0.5f) * shown * 0.04f;
                pl.setPitch(Math.max(-90f, Math.min(90f, pitch)));
                pl.setYaw(pl.getYaw() + (R.nextFloat() - 0.5f) * shown * 0.07f);
            }
            // Latido que se acelera
            if (intensity > 35f) {
                if (--heartbeat <= 0) {
                    float vol = 0.55f + intensity / 160f;
                    client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.0f, vol));
                    heartbeat = (int) (82 - intensity * 0.6f);
                }
            } else {
                heartbeat = 0;
            }
            // Respiración agitada
            if (shown > 65f) {
                if (--breath <= 0) {
                    client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_BREATH, 0.8f, 0.6f));
                    breath = 90 + R.nextInt(60);
                }
            }
            // Ojos que parpadean en los bordes de la pantalla
            if (eyes > 0) {
                eyes--;
            } else if (intensity > 40f && R.nextInt(600) < intensity / 9f) {
                eyes = 6 + R.nextInt(8);
                eyeX = R.nextBoolean() ? 0.05f + R.nextFloat() * 0.2f : 0.75f + R.nextFloat() * 0.2f;
                eyeY = 0.15f + R.nextFloat() * 0.6f;
            }
            // Palabras que casi no se ven
            if (whisper > 0) {
                whisper--;
            } else if (intensity > 55f && R.nextInt(900) < intensity / 12f) {
                whisper = 8 + R.nextInt(10);
                whisperIdx = R.nextInt(WHISPERS.length);
                whisperX = 0.05f + R.nextFloat() * 0.7f;
                whisperY = 0.1f + R.nextFloat() * 0.7f;
            }
            // Fallos de imagen
            if (glitch > 0) glitch--;
            else if (shown > 50f && R.nextInt(700) < shown / 14f) glitch = 3 + R.nextInt(4);
        });

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null || mc.options.hudHidden) return;
            int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
            TextRenderer tr = mc.textRenderer;
            float s = Math.max(shown, shownPar * 0.7f);

            // Cielo gris sepia perpetuo (corrupción)
            if (sepia > 0.01f) {
                int a = (int) (255 * Math.min(0.6f, sepia));
                ctx.fill(0, 0, w, h, (a << 24) | 0x8A6F4A);
            }

            // Apocalipsis (día 5): rojo oscuro, apagones y parpadeo constante
            if (stage >= 3) {
                float fl = 0.6f + 0.4f * (float) Math.sin(System.currentTimeMillis() / 140.0);
                ctx.fill(0, 0, w, h, ((int) (38 * fl) << 24) | 0x480000);
                if (blackout > 0) ctx.fill(0, 0, w, h, 0x77000000);
            }

            if (s > 8f) {
                // Viñeta negra que se cierra con el miedo
                float a = Math.min(stage >= 3 ? 0.5f : 0.9f, s / 100f * 0.9f);
                int steps = 10, tw = Math.max(1, w / 60), th = Math.max(1, h / 40);
                for (int i = 0; i < steps; i++) {
                    float k = 1f - i / (float) steps;
                    int col = ((int) (255 * a * k * k)) << 24;
                    ctx.fill(i * tw, 0, (i + 1) * tw, h, col);
                    ctx.fill(w - (i + 1) * tw, 0, w - i * tw, h, col);
                    ctx.fill(0, i * th, w, (i + 1) * th, col);
                    ctx.fill(0, h - (i + 1) * th, w, h - i * th, col);
                }
                // Pulso rojo al ritmo del corazón
                double period = Math.max(240.0, 800.0 - s * 5.5);
                float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / period * Math.PI));
                int ra = (int) (255 * 0.2f * (s / 100f) * pulse);
                ctx.fill(0, 0, w, h, (ra << 24) | 0x00600000);
            }
            // Estática en pantalla con mucho miedo
            if (shown > 60f) {
                int n = (int) ((shown - 55f) * 0.9f);
                for (int i = 0; i < n; i++) {
                    int x = R.nextInt(w), y = R.nextInt(h);
                    int g = 40 + R.nextInt(120);
                    ctx.fill(x, y, x + 1 + R.nextInt(3), y + 1, (60 << 24) | (g << 16) | (g << 8) | g);
                }
            }
            if (glitch > 0) {
                int y = R.nextInt(h);
                ctx.fill(0, y, w, y + 2 + R.nextInt(6), 0x55000000 | (R.nextInt(0x1000000) & 0x00FFFFFF));
            }
            if (eyes > 0) {
                int cx = (int) (w * eyeX), cy = (int) (h * eyeY);
                ctx.fill(cx - 14, cy, cx - 6, cy + 3, 0xFFFFFFFF);
                ctx.fill(cx + 6, cy, cx + 14, cy + 3, 0xFFFFFFFF);
            }
            if (whisper > 0) {
                ctx.drawText(tr, Text.literal(WHISPERS[whisperIdx]), (int) (w * whisperX), (int) (h * whisperY), 0x66B0B0B0, false);
            }

            // ---- Cuenta atrás del apocalipsis (arriba al centro, en rojo) ----
            if (timer >= 0) {
                if (timer > timerMax) timerMax = timer;
                boolean urgent = timer <= 10;
                boolean blink = urgent && (System.currentTimeMillis() / 250) % 2 == 0;
                String tt = String.format("%d:%02d", timer / 60, timer % 60);
                Text tx = Text.literal(tt);
                int tw = 112, th = 40;
                int sx = urgent ? R.nextInt(3) - 1 : 0;
                int tx0 = w / 2 - tw / 2 + sx, ty0 = 6;
                float pul = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / (urgent ? 90.0 : 260.0));
                int edge = 0xFF000000 | ((int) (150 + 100 * pul) << 16) | 0x1010;
                ctx.fill(tx0 - 1, ty0 - 1, tx0 + tw + 1, ty0 + th + 1, 0xFF07040B);
                ctx.fill(tx0, ty0, tx0 + tw, ty0 + th, 0xDD1A0508);
                ctx.fill(tx0, ty0, tx0 + tw, ty0 + 1, edge);
                ctx.fill(tx0, ty0 + th - 1, tx0 + tw, ty0 + th, edge);
                ctx.fill(tx0, ty0, tx0 + 1, ty0 + th, edge);
                ctx.fill(tx0 + tw - 1, ty0, tx0 + tw, ty0 + th, edge);
                ctx.drawText(tr, TIMER_CAP, w / 2 - tr.getWidth(TIMER_CAP) / 2 + sx, ty0 + 4, 0xFFC08080, false);
                var mt = ctx.getMatrices();
                mt.push();
                mt.translate(w / 2f + sx, ty0 + 14, 0);
                mt.scale(2.2f, 2.2f, 1f);
                ctx.drawTextWithShadow(tr, tx, -tr.getWidth(tx) / 2, 0, blink ? 0xFFFFFFFF : 0xFFFF2A2A);
                mt.pop();
                int barW = tw - 12;
                ctx.fill(tx0 + 6, ty0 + th - 7, tx0 + 6 + barW, ty0 + th - 4, 0xFF2A0A0E);
                int fillW = timerMax > 0 ? (int) (barW * (timer / (float) timerMax)) : 0;
                ctx.fill(tx0 + 6, ty0 + th - 7, tx0 + 6 + fillW, ty0 + th - 4, blink ? 0xFFFFFFFF : 0xFFD01818);
            } else {
                timerMax = 0;
            }

            // ---- Contador de días (arriba a la derecha) ----
            if (hud) {
                if (dayShown != dayTextFor) { dayTextFor = dayShown; dayText = Text.literal(String.valueOf(dayShown)); }
                // Contador de días: panel con marco, "DÍA", el número grande y 5 marcas (una por día de la historia)
                int cw = 60, chh = 38;
                float jx = shown > 70f && R.nextInt(8) == 0 ? (R.nextFloat() - 0.5f) * 3f : 0f;
                int cx0 = w - 8 - cw + (int) jx, cy0 = 6;
                int accent = stage >= 3 ? 0xFFD02020 : stage >= 2 ? 0xFFB89868 : 0xFFC9A24B;
                ctx.fill(cx0 - 1, cy0 - 1, cx0 + cw + 1, cy0 + chh + 1, 0xFF07040B);
                ctx.fill(cx0, cy0, cx0 + cw, cy0 + chh, 0xCC120A18);
                ctx.fill(cx0, cy0, cx0 + cw, cy0 + 1, accent);
                ctx.fill(cx0, cy0 + chh - 1, cx0 + cw, cy0 + chh, accent);
                ctx.fill(cx0 - 2, cy0 - 2, cx0 + 2, cy0 + 2, accent);
                ctx.fill(cx0 + cw - 2, cy0 - 2, cx0 + cw + 2, cy0 + 2, accent);
                ctx.fill(cx0 - 2, cy0 + chh - 2, cx0 + 2, cy0 + chh + 2, accent);
                ctx.fill(cx0 + cw - 2, cy0 + chh - 2, cx0 + cw + 2, cy0 + chh + 2, accent);
                ctx.drawText(tr, DAY_CAP, cx0 + cw / 2 - tr.getWidth(DAY_CAP) / 2, cy0 + 4, 0xFFA898B8, false);
                float tday = Math.min(1f, (dayShown - 1) / 4f);
                int cg = (int) (255 * (1f - tday * 0.8f));
                int ncol = stage >= 2 ? 0xFFE8D8B0 : 0xFF000000 | (255 << 16) | (cg << 8) | cg;
                var ms = ctx.getMatrices();
                ms.push();
                ms.translate(cx0 + cw / 2f, cy0 + 13, 0);
                ms.scale(2.2f, 2.2f, 1f);
                ctx.drawTextWithShadow(tr, dayText, -tr.getWidth(dayText) / 2, 0, ncol);
                ms.pop();
                for (int i = 0; i < 5; i++) {
                    int px = cx0 + (cw - 42) / 2 + i * 9;
                    boolean on = i < dayShown;
                    int pc = on ? (i == 4 ? 0xFFD02020 : accent) : 0xFF3A2C46;
                    ctx.fill(px, cy0 + chh - 7, px + 6, cy0 + chh - 4, pc);
                }

                // ---- Barra de miedo y paranoia (encima de la barra de vida) ----
                // Una sola barra de PARANOIA: marco ornamental, degradado violeta -> rojo, marcas, brillo y un ojo que se abre
                int bx = w / 2 - 80, by = h - 62, bw = 81, bh = 7;
                float v = Math.min(100f, Math.max(shown, shownPar * 0.9f));
                long now = System.currentTimeMillis();
                boolean hot = v > 65f;
                float beat = hot ? 0.5f + 0.5f * (float) Math.sin(now / Math.max(120.0, 420.0 - v * 2.5)) : 0f;
                int frame = hot ? (0xFF000000 | ((int) (120 + 110 * beat) << 16) | 0x101018) : 0xFF8A6D3B;
                ctx.fill(bx - 3, by - 3, bx + bw + 3, by + bh + 3, 0xFF07040B);          // sombra exterior
                ctx.fill(bx - 2, by - 2, bx + bw + 2, by + bh + 2, frame);                // marco
                ctx.fill(bx - 1, by - 1, bx + bw + 1, by + bh + 1, 0xFF150C1C);          // fondo
                int fw = (int) (bw * v / 100f);
                for (int i = 0; i < fw; i++) {
                    float tt = i / (float) bw;
                    float sh = 0.9f + 0.1f * (float) Math.sin(now / 180.0 + i * 0.35);
                    int cr = (int) ((100 + 110 * tt) * sh), cg = (int) ((30 - 18 * tt) * sh), cb = (int) ((190 - 150 * tt) * sh);
                    ctx.fill(bx + i, by, bx + i + 1, by + bh, 0xFF000000 | (Math.min(255, cr) << 16) | (Math.max(0, cg) << 8) | Math.max(0, cb));
                }
                if (fw > 0) ctx.fill(bx, by, bx + fw, by + 1, 0x55FFFFFF);               // brillo superior
                if (fw > 0) ctx.fill(bx, by + bh - 1, bx + fw, by + bh, 0x66000000);     // sombra inferior
                for (int q = 1; q <= 3; q++) ctx.fill(bx + bw * q / 4, by, bx + bw * q / 4 + 1, by + bh, 0x99000000);
                // adornos: rombos dorados en los extremos
                int gold = 0xFFC9A24B;
                ctx.fill(bx - 5, by + 2, bx - 3, by + 5, gold);
                ctx.fill(bx + bw + 3, by + 2, bx + bw + 5, by + 5, gold);
                ctx.fill(bx - 4, by + 1, bx - 3, by + 6, gold);
                ctx.fill(bx + bw + 3, by + 1, bx + bw + 4, by + 6, gold);
                // el ojo: cuanto más paranoia, más abierto y más inquieto
                int ex = bx - 17, ey = by - 1;
                int open = 1 + (int) (v / 100f * 3.5f);
                ctx.fill(ex, ey + 4 - open, ex + 10, ey + 5 + open, 0xFFE8E0D0);
                int jit = v > 60f && R.nextInt(3) == 0 ? R.nextInt(3) - 1 : 0;
                ctx.fill(ex + 3 + jit, ey + 4 - Math.min(open, 2), ex + 7 + jit, ey + 5 + Math.min(open, 2), hot ? 0xFFE01818 : 0xFF2A1A44);
                ctx.fill(ex - 1, ey + 3 - open, ex + 11, ey + 4 - open, 0xFF07040B);
                ctx.fill(ex - 1, ey + 5 + open, ex + 11, ey + 6 + open, 0xFF07040B);
                int lc = hot ? 0xFFE05050 : 0xFFB8A0D0;
                ctx.drawText(tr, BAR_LABEL, bx, by - 13, lc, true);
            }

            // Título de bienvenida en el centro de la pantalla
            if (introTicks > 0) {
                float fade = Math.min(1f, Math.min(introTicks, INTRO_LEN - introTicks) / 20f);
                int a = (int) (255 * fade);
                // bandas de cine y velo oscuro
                int band = (int) (h * 0.13f * fade);
                ctx.fill(0, 0, w, band, (a << 24));
                ctx.fill(0, h - band, w, h, (a << 24));
                ctx.fill(0, 0, w, h, ((int) (120 * fade) << 24));
                if (a >= 8) {
                    int cxm = w / 2, cym = h / 2 - 14;
                    int gold = (a << 24) | 0xC9A24B;
                    int ln = Math.max(40, w / 6);
                    // filete con rombo central, arriba y abajo del título
                    for (int side = -1; side <= 1; side += 2) {
                        int yy = cym + side * 30;
                        ctx.fill(cxm - ln, yy, cxm - 6, yy + 1, gold);
                        ctx.fill(cxm + 6, yy, cxm + ln, yy + 1, gold);
                        ctx.fill(cxm - 3, yy - 2, cxm + 3, yy + 3, gold);
                        ctx.fill(cxm - 1, yy - 4, cxm + 1, yy + 5, gold);
                    }
                    Text big = Text.literal("DÍA " + dayShown);
                    Text sub = Text.literal("de " + introScramble);
                    var ms2 = ctx.getMatrices();
                    ms2.push();
                    ms2.translate(cxm, cym - 12, 0);
                    ms2.scale(4f, 4f, 1f);
                    ctx.drawTextWithShadow(tr, big, -tr.getWidth(big) / 2, 0, (a << 24) | 0xF2E8D0);
                    ms2.pop();
                    ms2.push();
                    ms2.translate(cxm, cym + 12, 0);
                    ms2.scale(1.8f, 1.8f, 1f);
                    ctx.drawTextWithShadow(tr, sub, -tr.getWidth(sub) / 2, 0, (a << 24) | 0xB8A0D0);
                    ms2.pop();
                }
            }

            if (flash > 0) {
                float f = flash / (float) flashMax;
                if (f > 0.8f) {
                    ctx.fill(0, 0, w, h, 0xE6FFFFFF);
                } else {
                    int al = (int) (255 * Math.min(1f, f * 1.4f));
                    ctx.fill(0, 0, w, h, al << 24);
                    int ew = w / 9, eh = h / 16;
                    int white = ((int) (255 * Math.min(1f, f * 1.6f)) << 24) | 0xFFFFFF;
                    ctx.fill(w * 36 / 100, h * 42 / 100, w * 36 / 100 + ew, h * 42 / 100 + eh, white);
                    ctx.fill(w * 64 / 100 - ew, h * 42 / 100, w * 64 / 100, h * 42 / 100 + eh, white);
                    if (flashKind >= 2) { // la boca: una línea larga y dentada
                        int my = h * 62 / 100, mx0 = w * 34 / 100, mx1 = w * 66 / 100;
                        ctx.fill(mx0, my, mx1, my + h / 22, white);
                        for (int x = mx0; x < mx1; x += Math.max(4, w / 40)) {
                            ctx.fill(x, my - h / 40, x + Math.max(2, w / 80), my, white);
                            ctx.fill(x + Math.max(2, w / 80), my + h / 22, x + Math.max(4, w / 40), my + h / 22 + h / 40, white);
                        }
                    }
                }
            }
        });
    }
}
