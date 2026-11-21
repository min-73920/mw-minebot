package com.haiselita.mwminebot.asm;

import java.util.Map;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

/** Hooks click-hold and pause checks so bots can keep digging when backgrounded. */
@IFMLLoadingPlugin.MCVersion("1.8.9")
@IFMLLoadingPlugin.SortingIndex(2000)
public class MWMineBotPlugin implements IFMLLoadingPlugin {
    @Override public String[] getASMTransformerClass() { return new String[]{"com.haiselita.mwminebot.asm.MineBotTransformer"}; }
    @Override public String getModContainerClass() { return null; }
    @Override public String getSetupClass() { return null; }
    @Override public void injectData(Map<String, Object> data) {}
    @Override public String getAccessTransformerClass() { return null; }
}
