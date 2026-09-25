package ai.minecivilization.client;

import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.entity.WorkAnimation;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * The vanilla humanoid model plus small semantic work poses.
 *
 * <p>LivingEntity's swing packet remains the low-latency animation. These
 * additive rotations make a worker visibly face the kind of job it is doing:
 * a tree is chopped differently from a block being placed, while the vanilla
 * walk/crouch/item-use poses are preserved by calling super first.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class CitizenModel extends HumanoidModel<CitizenEntity> {

    public CitizenModel(ModelPart root) {
        super(root);
    }

    @Override
    public void setupAnim(CitizenEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headXRot) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks,
                netHeadYaw, headXRot);

        WorkAnimation action = entity.getWorkAnimation();
        if (action == WorkAnimation.NONE) return;

        long start = entity.getWorkAnimationStart();
        long now = entity.level() == null ? 0L : entity.level().getGameTime();
        float age = start <= 0L ? ageInTicks
                : Math.max(0.0f, now - start);
        float phase = (age % 10.0f) / 10.0f * (float) (Math.PI * 2.0);
        float wave = (float) Math.sin(phase);
        float counterWave = -wave;

        switch (action) {
            case MINE -> {
                rightArm.xRot += -0.95f + wave * 0.32f;
                rightArm.yRot += -0.12f;
                leftArm.xRot += -0.25f + counterWave * 0.10f;
            }
            case CHOP -> {
                rightArm.xRot += -1.20f + wave * 0.48f;
                rightArm.zRot += -0.10f;
                leftArm.xRot += -0.55f + counterWave * 0.18f;
                leftArm.zRot += 0.08f;
            }
            case PLACE -> {
                rightArm.xRot += -1.05f + wave * 0.22f;
                rightArm.yRot += 0.08f;
                leftArm.xRot += -0.18f + counterWave * 0.08f;
            }
            case BUILD -> {
                rightArm.xRot += -0.82f + wave * 0.18f;
                leftArm.xRot += -0.30f + counterWave * 0.12f;
            }
            case CRAFT, SMELT, REACH -> {
                rightArm.xRot += -0.62f + wave * 0.16f;
                leftArm.xRot += -0.62f + counterWave * 0.16f;
                rightArm.yRot += 0.04f;
                leftArm.yRot -= 0.04f;
            }
            case HARVEST, FORAGE -> {
                rightArm.xRot += -0.48f + wave * 0.24f;
                leftArm.xRot += -0.12f + counterWave * 0.08f;
            }
            case TILL -> {
                rightArm.xRot += -0.78f + wave * 0.34f;
                rightArm.yRot -= 0.08f;
                leftArm.xRot += -0.18f;
            }
            case PLANT -> {
                rightArm.xRot += -0.38f + wave * 0.18f;
                leftArm.xRot += -0.18f + counterWave * 0.08f;
            }
            case EAT -> {
                rightArm.xRot += -0.30f + wave * 0.10f;
            }
            case COMBAT -> {
                rightArm.xRot += -0.72f + wave * 0.18f;
                leftArm.xRot += -0.12f;
            }
            case NONE -> {
                // Kept explicit for exhaustive switch updates.
            }
        }
    }
}
