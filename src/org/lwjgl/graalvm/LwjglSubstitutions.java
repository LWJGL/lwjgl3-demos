package org.lwjgl.graalvm;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.system.Pointer.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

@TargetClass(org.lwjgl.system.ThreadLocalUtil.class)
final class Target_org_lwjgl_system_ThreadLocalUtil {

    @Alias
    private static long JNI_NATIVE_INTERFACE;

    @Alias
    private static long FUNCTION_MISSING_ABORT;

    @Substitute
    public static void setFunctionMissingAddresses(Class<?> capabilitiesClass, int index) {
        if (capabilitiesClass == null) {
            long missingCaps = memGetAddress(JNI_NATIVE_INTERFACE + Integer.toUnsignedLong(index) * POINTER_SIZE);
            if (missingCaps != NULL) {
                getAllocator().free(missingCaps);
                memPutAddress(JNI_NATIVE_INTERFACE + Integer.toUnsignedLong(index) * POINTER_SIZE, NULL);
            }
        } else {
            int functionCount = getFieldsFromCapabilities(capabilitiesClass).size();

            long missingCaps = getAllocator().malloc(Integer.toUnsignedLong(functionCount) * POINTER_SIZE);
            for (int i = 0; i < functionCount; i++) {
                memPutAddress(missingCaps + Integer.toUnsignedLong(i) * POINTER_SIZE, FUNCTION_MISSING_ABORT);
            }

            //the whole purpose of substituting this method is just to remove the following line
            //(which causes the resulting native image to crash!)
            //memPutAddress(JNI_NATIVE_INTERFACE + Integer.toUnsignedLong(index) * POINTER_SIZE, missingCaps);
        }
    }

    //copied verbatim from the original class
    @Substitute
    private static List<Field> getFieldsFromCapabilities(Class<?> capabilitiesClass) {
        List<Field> fields = new ArrayList<>();
        for (Field field : capabilitiesClass.getFields()) {
            if (field.getType() == long.class) {
                fields.add(field);
            }
        }
        return fields;
    }
}

/** Dummy class with the file's name. */
public class LwjglSubstitutions {
}
