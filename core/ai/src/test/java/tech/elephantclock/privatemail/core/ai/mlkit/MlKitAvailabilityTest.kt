package tech.elephantclock.privatemail.core.ai.mlkit

import com.google.mlkit.genai.common.FeatureStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.elephantclock.privatemail.core.ai.ModelStatus
import tech.elephantclock.privatemail.core.ai.UnavailableReason

class MlKitAvailabilityTest {
    @Test
    fun `every ML Kit feature status maps onto a model status`() {
        assertEquals(ModelStatus.Available, MlKitAvailability.statusOf(FeatureStatus.AVAILABLE))
        assertEquals(ModelStatus.Downloadable(), MlKitAvailability.statusOf(FeatureStatus.DOWNLOADABLE))
        assertEquals(ModelStatus.Downloading(), MlKitAvailability.statusOf(FeatureStatus.DOWNLOADING))
        assertEquals(
            ModelStatus.Unavailable(UnavailableReason.NOT_SUPPORTED),
            MlKitAvailability.statusOf(FeatureStatus.UNAVAILABLE),
        )
        assertEquals(ModelStatus.Unavailable(UnavailableReason.NOT_READY), MlKitAvailability.statusOf(-42))
    }
}
