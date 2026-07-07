/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
 */

package net.kollnig.missioncontrol.patch;

import androidx.annotation.NonNull;

import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.MethodParameter;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.ImmutableMethodImplementation;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Applies the {@link SmaliPatches} declarative method-body replacements to a
 * single loaded {@link DexFile}, returning a new immutable dex with matched
 * method bodies replaced by no-op / always-accept stubs. Uses smali's dexlib2
 * (already a dependency) — no external patcher required. Only classes that live
 * in the app's own dex files are patchable; framework classes cannot be touched.
 */
final class DexPatcher {

    /** Result of patching one dex file. */
    static final class Result {
        final DexFile dex;             // rewritten dex, null when nothing matched
        final byte[] bytes;            // serialized patched dex, null if not yet written
        final int patchedMethods;

        Result(DexFile dex, byte[] bytes, int patchedMethods) {
            this.dex = dex;
            this.bytes = bytes;
            this.patchedMethods = patchedMethods;
        }

        Result(DexFile dex, int patchedMethods) {
            this(dex, null, patchedMethods);
        }
    }

    /**
     * Patch every class in {@code dex} and return the rewritten dex + count.
     *
     * <p>Only the classes that actually match a {@link SmaliPatches} selector are
     * materialized into new {@link ImmutableClassDef}s; every other class is
     * passed through by reference to its original lazy {@code DexBackedClassDef},
     * which reads from the backing dex buffer on demand. This keeps peak memory
     * proportional to the (tiny) set of patched classes rather than to the whole
     * dex — deep-copying every class was what exhausted the heap on large apps.
     * When nothing matches, {@link Result#dex} is null so the caller can leave
     * the original dex untouched.</p>
     */
    @NonNull
    static Result patchDex(@NonNull DexFile dex) {
        final List<ClassDef> outClasses = new ArrayList<>();
        int patched = 0;
        for (ClassDef classDef : dex.getClasses()) {
            int[] count = {0};
            ImmutableClassDef rewritten = patchClass(classDef, count);
            if (rewritten != null) {
                patched += count[0];
                outClasses.add(rewritten);
            } else {
                outClasses.add(classDef); // pass-through, lazy — no deep copy
            }
        }
        if (patched == 0) return new Result(null, 0);

        final Opcodes opcodes = dex.getOpcodes();
        DexFile out = new DexFile() {
            @NonNull
            @Override
            public Set<? extends ClassDef> getClasses() {
                return new AbstractSet<ClassDef>() {
                    @NonNull
                    @Override
                    public Iterator<ClassDef> iterator() {
                        return outClasses.iterator();
                    }

                    @Override
                    public int size() {
                        return outClasses.size();
                    }
                };
            }

            @NonNull
            @Override
            public Opcodes getOpcodes() {
                return opcodes;
            }
        };
        return new Result(out, patched);
    }

    /**
     * @return a new ImmutableClassDef with matched methods replaced, or null
     * if no selector matched (caller keeps the original via ImmutableClassDef.of).
     */
    private static ImmutableClassDef patchClass(@NonNull ClassDef classDef, int[] count) {
        List<String> interfaces = new ArrayList<>();
        for (String i : classDef.getInterfaces()) interfaces.add(i);

        List<SmaliPatches.Selector> matched = matchedSelectors(classDef.getType(), interfaces);
        if (matched.isEmpty()) return null;

        List<ImmutableMethod> directMethods = new ArrayList<>();
        List<ImmutableMethod> virtualMethods = new ArrayList<>();

        for (Method m : classDef.getDirectMethods()) {
            ImmutableMethod r = patchMethod(m, matched);
            if (r != null) {
                directMethods.add(r);
                count[0]++;
            } else {
                directMethods.add(ImmutableMethod.of(m));
            }
        }
        for (Method m : classDef.getVirtualMethods()) {
            ImmutableMethod r = patchMethod(m, matched);
            if (r != null) {
                virtualMethods.add(r);
                count[0]++;
            } else {
                virtualMethods.add(ImmutableMethod.of(m));
            }
        }

        return new ImmutableClassDef(
                classDef.getType(),
                classDef.getAccessFlags(),
                classDef.getSuperclass(),
                interfaces,
                classDef.getSourceFile(),
                classDef.getAnnotations(),
                classDef.getStaticFields(),
                classDef.getInstanceFields(),
                directMethods,
                virtualMethods);
    }

    /** @return a replacement ImmutableMethod, or null if no patch matched. */
    private static ImmutableMethod patchMethod(@NonNull Method m,
                                               @NonNull List<SmaliPatches.Selector> selectors) {
        MethodImplementation impl = m.getImplementation();
        if (impl == null) return null; // abstract / native
        for (SmaliPatches.Selector s : selectors) {
            for (SmaliPatches.MethodPatch p : s.methods) {
                if (matches(m, p)) {
                    return replace(m, impl, p);
                }
            }
        }
        return null;
    }

    private static boolean matches(@NonNull Method m, @NonNull SmaliPatches.MethodPatch p) {
        if (!m.getName().equals(p.name)) return false;
        if (p.returnType != null && !m.getReturnType().equals(p.returnType)) return false;
        if (p.paramTypes == null) return true; // wildcard
        List<? extends MethodParameter> params = m.getParameters();
        if (params.size() != p.paramTypes.size()) return false;
        for (int i = 0; i < params.size(); i++) {
            if (!params.get(i).getType().equals(p.paramTypes.get(i))) return false;
        }
        return true;
    }

    private static ImmutableMethod replace(@NonNull Method m,
                                          @NonNull MethodImplementation impl,
                                          @NonNull SmaliPatches.MethodPatch p) {
        List<ImmutableInstruction> instrs = p.replacement.instructions(m.getReturnType());
        int regs = p.replacement.registerCount(impl.getRegisterCount());
        ImmutableMethodImplementation newImpl = new ImmutableMethodImplementation(
                regs, instrs, Collections.emptyList(), Collections.emptyList());
        return new ImmutableMethod(
                m.getDefiningClass(),
                m.getName(),
                m.getParameters(),
                m.getReturnType(),
                m.getAccessFlags(),
                m.getAnnotations(),
                m.getHiddenApiRestrictions(),
                newImpl);
    }

    private static List<SmaliPatches.Selector> matchedSelectors(
            @NonNull String type, @NonNull List<String> interfaces) {
        List<SmaliPatches.Selector> result = new ArrayList<>();
        for (SmaliPatches.Selector s : SmaliPatches.all()) {
            if (s.isInterface) {
                if (interfaces.contains(s.type)) result.add(s);
            } else if (s.type.equals(type)) {
                result.add(s);
            }
        }
        return result;
    }

    private DexPatcher() {
    }
}
