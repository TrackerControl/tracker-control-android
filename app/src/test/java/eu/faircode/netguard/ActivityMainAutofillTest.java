package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import net.kollnig.missioncontrol.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
public class ActivityMainAutofillTest {
    private static final String OTHER_SERVICE = "activity-main-test-service";
    private static final String TAG = "TrackerControl.Main";

    @Before
    public void clearLogs() {
        ShadowLog.clear();
    }

    @Test
    public void recognizedFailureIsSuppressedAndLatched() {
        NullPointerException failure = parcelFileDescriptorFailure("FileDescriptor must not be null", "<init>");
        TestContext context = new TestContext(failure, null, Context.AUTOFILL_SERVICE);
        ActivityMain activity = activityWith(context);

        assertNull(activity.getSystemService(Context.AUTOFILL_SERVICE));
        assertNull(activity.getSystemService(Context.AUTOFILL_SERVICE));
        assertSame(context.otherService, activity.getSystemService(OTHER_SERVICE));

        assertEquals(1, context.autofillLookups);
        assertEquals(1, context.otherServiceLookups);
        assertEquals(1, ShadowLog.getLogsForTag(TAG).size());
        assertSame(failure, ShadowLog.getLogsForTag(TAG).get(0).throwable);
    }

    @Test
    public void actionBarInflatesWhenAutofillDescriptorIsMissing() {
        TestContext context = new TestContext(
                parcelFileDescriptorFailure("FileDescriptor must not be null", "<init>"),
                null, Context.AUTOFILL_SERVICE);
        Context themed = new ContextThemeWrapper(activityWith(context), R.style.AppTheme);

        View actionBar = LayoutInflater.from(themed).inflate(R.layout.actionmain, null, false);

        assertNotNull(actionBar.findViewById(R.id.swEnabled));
        assertNotNull(actionBar.findViewById(R.id.tvTitle));
        assertEquals(1, context.autofillLookups);
    }

    @Test
    public void normalAutofillResultsPassThrough() {
        Object service = new Object();
        TestContext context = new TestContext(null, service, null);
        ActivityMain activity = activityWith(context);

        assertSame(service, activity.getSystemService(Context.AUTOFILL_SERVICE));

        TestContext nullContext = new TestContext(null, null, null);
        ActivityMain nullActivity = activityWith(nullContext);
        assertNull(nullActivity.getSystemService(Context.AUTOFILL_SERVICE));
        assertNull(nullActivity.getSystemService(Context.AUTOFILL_SERVICE));
        assertEquals(2, nullContext.autofillLookups);
    }

    @Test
    public void sameFailureForOtherServicePropagates() {
        NullPointerException failure = parcelFileDescriptorFailure("FileDescriptor must not be null", "<init>");
        TestContext context = new TestContext(failure, null, OTHER_SERVICE);

        assertPropagated(failure, activityWith(context), OTHER_SERVICE);
        assertEquals(1, context.otherServiceLookups);
    }

    @Test
    public void nearMissesAndOtherExceptionsPropagate() {
        NullPointerException wrongMessage = parcelFileDescriptorFailure(
                "different message", "<init>");
        assertPropagated(wrongMessage,
                activityWith(new TestContext(wrongMessage, null, Context.AUTOFILL_SERVICE)),
                Context.AUTOFILL_SERVICE);

        NullPointerException wrongMethod = parcelFileDescriptorFailure(
                "FileDescriptor must not be null", "read");
        assertPropagated(wrongMethod,
                activityWith(new TestContext(wrongMethod, null, Context.AUTOFILL_SERVICE)),
                Context.AUTOFILL_SERVICE);

        NullPointerException wrongClass = new NullPointerException("FileDescriptor must not be null");
        wrongClass.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("android.os.OtherFileDescriptor", "<init>",
                        "OtherFileDescriptor.java", 123),
        });
        assertPropagated(wrongClass,
                activityWith(new TestContext(wrongClass, null, Context.AUTOFILL_SERVICE)),
                Context.AUTOFILL_SERVICE);

        RuntimeException otherFailure = new IllegalStateException("service failed");
        assertPropagated(otherFailure,
                activityWith(new TestContext(otherFailure, null, Context.AUTOFILL_SERVICE)),
                Context.AUTOFILL_SERVICE);
    }

    @Test
    public void newActivityRetriesAfterPreviousActivityFailure() {
        TestContext context = new TestContext(
                parcelFileDescriptorFailure("FileDescriptor must not be null", "<init>"),
                null, Context.AUTOFILL_SERVICE);

        assertNull(activityWith(context).getSystemService(Context.AUTOFILL_SERVICE));
        assertNull(activityWith(context).getSystemService(Context.AUTOFILL_SERVICE));

        assertEquals(2, context.autofillLookups);
    }

    private static ActivityMain activityWith(Context context) {
        ActivityMain activity = new ActivityMain();
        ReflectionHelpers.setField(activity, "mBase", context);
        return activity;
    }

    private static NullPointerException parcelFileDescriptorFailure(String message, String method) {
        NullPointerException failure = new NullPointerException(message);
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("android.os.ParcelFileDescriptor", method,
                        "ParcelFileDescriptor.java", 123),
        });
        return failure;
    }

    private static void assertPropagated(RuntimeException expected, ActivityMain activity, String service) {
        try {
            activity.getSystemService(service);
            fail("Expected the service lookup to propagate its exception");
        } catch (RuntimeException actual) {
            assertSame(expected, actual);
        }
    }

    private static final class TestContext extends ContextWrapper {
        private final RuntimeException failure;
        private final Object autofillService;
        private final String failingService;
        private final Object otherService = new Object();
        private int autofillLookups;
        private int otherServiceLookups;

        private TestContext(RuntimeException failure, Object autofillService, String failingService) {
            super(RuntimeEnvironment.getApplication());
            this.failure = failure;
            this.autofillService = autofillService;
            this.failingService = failingService;
        }

        @Override
        public Object getSystemService(String name) {
            if (Context.AUTOFILL_SERVICE.equals(name)) {
                autofillLookups++;
                if (Context.AUTOFILL_SERVICE.equals(failingService))
                    throw failure;
                return autofillService;
            }
            if (OTHER_SERVICE.equals(name)) {
                otherServiceLookups++;
                if (OTHER_SERVICE.equals(failingService))
                    throw failure;
                return otherService;
            }
            return super.getSystemService(name);
        }

    }
}
