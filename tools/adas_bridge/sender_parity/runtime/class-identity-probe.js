// Optional observation only: compare the class/method used to install a hook
// with actual original runtime objects. No SDK getters or state writes.
globalThis.DenzaClassIdentityProbe = (() => {
  const classes = new Map(), sampled = new Set();
  const label = c => c === null ? null : String(c.toString());
  return {
    retain(name, wrapper, overloads) {
      if (!classes.has(name)) classes.set(name, {klass: Java.retain(wrapper.class), methods: new Map()});
      const entry = classes.get(name);
      for (const [method, overload] of overloads) entry.methods.set(method, overload);
    },
    sample(stage, name, object) {
      if (object === null || sampled.has(stage)) return null;
      sampled.add(stage);
      const entry = classes.get(name);
      if (!entry) return {stage, error:'hook class not retained', name};
      const actual = Java.cast(object, Java.use('java.lang.Object')).getClass();
      const result = {stage, name, actual_name:String(actual.getName()),
        same_class:entry.klass.equals(actual), expected_loader:label(entry.klass.getClassLoader()),
        actual_loader:label(actual.getClassLoader()), factory_loader:label(Java.classFactory.loader), methods:[]};
      const env=Java.vm.getEnv();
      for (const method of actual.getDeclaredMethods()) {
        const methodName=String(method.getName());
        if (!entry.methods.has(methodName)) continue;
        const overload=entry.methods.get(methodName), mangler=overload._r&&overload._r._m;
        result.methods.push({name:methodName, reflected: String(env.fromReflectedMethod(method.$h)),
          hook_method: mangler ? String(mangler.hookedMethodId) : null,
          wrapper_method: String(overload.handle), signature:String(method.toString())});
      }
      return result;
    },
    observer(stage, lifecycle) {
      const actual=Java.cast(lifecycle,Java.use('java.lang.Object')).getClass();
      for(const f of actual.getDeclaredFields()) if(String(f.getType().getName())==='k.h.e0.c.g') {
        f.setAccessible(true);return this.sample(stage,'k.h.e0.c.g',f.get(lifecycle));
      }
      return {stage,error:'original lifecycle has no declared g field'};
    }
  };
})();
