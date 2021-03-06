package net.roguelogix.phosphophyllite.registry;

import com.google.common.collect.ImmutableSet;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.FlowingFluidBlock;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderTypeLookup;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.client.renderer.model.ModelResourceLocation;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FlowingFluid;
import net.minecraft.fluid.Fluid;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.ContainerType;
import net.minecraft.item.*;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityType;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.GenerationStage;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.OreFeatureConfig;
import net.minecraft.world.gen.feature.template.RuleTest;
import net.minecraft.world.gen.placement.Placement;
import net.minecraft.world.gen.placement.TopSolidRangeConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ModelBakeEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.extensions.IForgeContainerType;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.event.world.BiomeLoadingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fluids.FluidAttributes;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistryEntry;
import net.roguelogix.phosphophyllite.config.ConfigManager;
import org.objectweb.asm.Type;

import javax.annotation.Nonnull;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class Registry {
    
    private static final HashMap<String, HashSet<Block>> blocksRegistered = new HashMap<>();
    private static final HashMap<Class<?>, HashSet<Block>> tileEntityBlocksToRegister = new HashMap<>();
    
    interface EventBS<T extends IForgeRegistryEntry<T>> extends Consumer<RegistryEvent.Register<T>> {
        
        @SubscribeEvent
        void accept(RegistryEvent.Register<T> event);
    }
    
    
    private static class ModEventHandler {
        
        String modNamespace;
        Set<Class<?>> classes;
        
        ModEventHandler(String namespace, Set<Class<?>> clazzes) {
            modNamespace = namespace;
            classes = clazzes;
        }
        
        @SubscribeEvent
        void blockRegistration(RegistryEvent.Register<Block> event) {
            registerBlocks(event, modNamespace, classes);
        }
        
        @SubscribeEvent
        void itemRegistration(RegistryEvent.Register<Item> event) {
            registerItems(event, modNamespace, classes);
        }
        
        @SubscribeEvent
        void fluidRegistration(RegistryEvent.Register<Fluid> event) {
            registerFluids(event, modNamespace, classes);
        }
        
        @SubscribeEvent
        void containerRegistration(RegistryEvent.Register<ContainerType<?>> event) {
            registerContainers(event, modNamespace, classes);
        }
        
        @SubscribeEvent
        void tileEntityRegistration(RegistryEvent.Register<TileEntityType<?>> event) {
            registerTileEntities(event, modNamespace, classes);
        }
    }
    
    @SuppressWarnings("unchecked")
    public static synchronized void onModLoad() {
        String callerClass = new Exception().getStackTrace()[1].getClassName();
        String callerPackage = callerClass.substring(0, callerClass.lastIndexOf("."));
        String modNamespace = callerPackage.substring(callerPackage.lastIndexOf(".") + 1);
        
        
        Set<Class<?>> classes =
                FMLLoader.getLoadingModList().getModFileById(modNamespace).getFile().getScanResult().getClasses().stream().map(classData -> {
                    try {
                        Field field = classData.getClass().getDeclaredField("clazz");
                        field.setAccessible(true);
                        Type clazz = (Type) field.get(classData);
                        String className = clazz.getClassName();
                        if (className.startsWith(callerPackage)) {
                            
                            if (FMLEnvironment.dist != Dist.CLIENT && className.contains("client")) {
                                return null;
                            }
                            if(className.contains("deps")){
                                return null;
                            }
                            return Registry.class.getClassLoader().loadClass(className);
                        }
                    } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
                        e.printStackTrace();
                    }
                    return null;
                }).filter(Objects::nonNull).collect(Collectors.toSet());
        
        classes.forEach(clazz -> {
            for (Method declaredMethod : clazz.getDeclaredMethods()) {
                if (Modifier.isStatic(declaredMethod.getModifiers())) {
                    if (declaredMethod.isAnnotationPresent(OnModLoad.class)) {
                        if (declaredMethod.getTypeParameters().length == 0) {
                            declaredMethod.setAccessible(true);
                            try {
                                declaredMethod.invoke(null);
                            } catch (IllegalAccessException | InvocationTargetException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        });
        
        
        // forge, im less than happy about this, let me use my lambda, ok, plz, thx
        FMLJavaModLoadingContext.get().getModEventBus().register(new ModEventHandler(modNamespace, classes));
        
        if (FMLEnvironment.dist == Dist.CLIENT) {
            FMLJavaModLoadingContext.get().getModEventBus().addListener((FMLClientSetupEvent e) -> onClientSetup(e, modNamespace, classes));
            FMLJavaModLoadingContext.get().getModEventBus().addListener((ModelBakeEvent e) -> onModelBake(e, modNamespace, classes));
            FMLJavaModLoadingContext.get().getModEventBus().addListener((TextureStitchEvent.Pre e) -> onTextureStitch(e, modNamespace, classes));
        }
        FMLJavaModLoadingContext.get().getModEventBus().addListener((FMLLoadCompleteEvent e) -> onLoadComplete(e, modNamespace, classes));
        
        MinecraftForge.EVENT_BUS.addListener((BiomeLoadingEvent biomeEvent) -> {
            registerWorldGen(modNamespace, classes, biomeEvent);
        });
        
        // oh yea, *right now*
        registerConfigs(modNamespace, classes);
    }
    
    private static synchronized void registerBlocks(final RegistryEvent.Register<Block> blockRegistryEvent, String modNamespace, Set<Class<?>> classes) {
        Set<Class<?>> blocks = classes.stream().filter(c -> c.isAnnotationPresent(RegisterBlock.class)).collect(Collectors.toSet());
        HashSet<Block> blocksRegistered = Registry.blocksRegistered
                .computeIfAbsent(modNamespace, k -> new HashSet<>());
        for (Class<?> block : blocks) {
            try {
                Constructor<?> constructor = block.getConstructor();
                constructor.setAccessible(true);
                Object newObject = constructor.newInstance();
                if (!(newObject instanceof Block)) {
                    // the fuck you doing
                    //todo print error
                    continue;
                }
                
                Block newBlock = (Block) newObject;
                RegisterBlock blockAnnotation = block.getAnnotation(RegisterBlock.class);
                newBlock.setRegistryName(modNamespace + ":" + blockAnnotation.name());
                blockRegistryEvent.getRegistry().register(newBlock);
                
                for (Field declaredField : block.getDeclaredFields()) {
                    if (declaredField.isAnnotationPresent(RegisterBlock.Instance.class)) {
                        declaredField.set(null, newBlock);
                    }
                }
                
                if (blockAnnotation.registerItem()) {
                    blocksRegistered.add(newBlock);
                }
                
                if (blockAnnotation.tileEntityClass() != RegisterBlock.class) {
                    tileEntityBlocksToRegister
                            .computeIfAbsent(blockAnnotation.tileEntityClass(), k -> new HashSet<>())
                            .add(newBlock);
                }
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                e.printStackTrace();
            }
        }
        
        Set<Class<?>> fluids = classes.stream().filter(c -> c.isAnnotationPresent(RegisterFluid.class)).collect(Collectors.toSet());
        for (Class<?> fluid : fluids) {
            RegisterFluid annotation = fluid.getAnnotation(RegisterFluid.class);
            for (Field declaredField : fluid.getDeclaredFields()) {
                if (declaredField.isAnnotationPresent(RegisterFluid.Instance.class)) {
                    FlowingFluidBlock block = new FlowingFluidBlock(() -> {
                        try {
                            return (FlowingFluid) declaredField.get(null);
                        } catch (IllegalAccessException e) {
                            e.printStackTrace();
                        }
                        return null;
                    }, Block.Properties.create(net.minecraft.block.material.Material.WATER).
                            doesNotBlockMovement().hardnessAndResistance(100.0F).noDrops()
                    );
                    block.setRegistryName(modNamespace + ":" + annotation.name() + "_block");
                    blockRegistryEvent.getRegistry().register(block);
                    break;
                }
            }
        }
    }
    
    
    private static synchronized void registerItems(final RegistryEvent.Register<Item> itemRegistryEvent, String modNamespace, Set<Class<?>> classes) {
        Set<Class<?>> items = classes.stream().filter(c -> c.isAnnotationPresent(RegisterItem.class)).collect(Collectors.toSet());
        HashSet<Block> blocksRegistered = Registry.blocksRegistered.get(modNamespace);
        
        Set<Class<?>> modClass = classes.stream().filter(c -> c.isAnnotationPresent(Mod.class)).collect(Collectors.toSet());
        
        String groupName = modNamespace;
        if (modClass.size() == 1) {
            groupName = modClass.iterator().next().getName();
        }
        
        // TODO: 6/28/20 check annotations for creative tab registration
        ItemGroup group;
        {
            Block block;
            Set<Class<?>> createiveTabBlock = classes.stream().filter(c -> c.isAnnotationPresent(CreativeTabBlock.class)).collect(Collectors.toSet());
            Iterator<Class<?>> iterator = createiveTabBlock.iterator();
            Block b1 = Blocks.STONE;
            if (iterator.hasNext()) {
                Class<?> blockClass = iterator.next();
                for (Field declaredField : blockClass.getDeclaredFields()) {
                    if (declaredField.isAnnotationPresent(RegisterBlock.Instance.class)) {
                        try {
                            b1 = (Block) declaredField.get(null);
                        } catch (IllegalAccessException e) {
                            e.printStackTrace();
                        }
                        break;
                    }
                }
            }
            block = b1;
            group = new ItemGroup(groupName) {
                @Nonnull
                @Override
                public ItemStack createIcon() {
                    return new ItemStack(ForgeRegistries.ITEMS.getValue(block.getRegistryName()));
                }

                @Override
                public void fill(NonNullList<ItemStack> items) {
                    super.fill(items);
                    items.sort((o1, o2) -> o1.getDisplayName().getString().compareToIgnoreCase(o2.getDisplayName().getString()));
                }
            };
        }
        
        Set<Class<?>> fluids = classes.stream().filter(c -> c.isAnnotationPresent(RegisterFluid.class)).collect(Collectors.toSet());
        for (Class<?> fluid : fluids) {
            RegisterFluid annotation = fluid.getAnnotation(RegisterFluid.class);
            if (!annotation.registerBucket()) {
                continue;
            }
            for (Field declaredField : fluid.getDeclaredFields()) {
                if (declaredField.isAnnotationPresent(RegisterFluid.Instance.class)) {
                    BucketItem item = new BucketItem(() -> {
                        try {
                            return (FlowingFluid) declaredField.get(null);
                        } catch (IllegalAccessException e) {
                            e.printStackTrace();
                        }
                        return null;
                    }, new Item.Properties().containerItem(Items.BUCKET).maxStackSize(1).group(group));
                    item.setRegistryName(modNamespace + ":" + annotation.name() + "_bucket");
                    itemRegistryEvent.getRegistry().register(item);
                    break;
                }
            }
        }
        
        for (Block block : blocksRegistered) {
            assert block.getClass().isAnnotationPresent(RegisterBlock.class);
//            RegisterBlock blockAnnotation = block.getClass().getAnnotation(RegisterBlock.class);
            //noinspection ConstantConditions
            itemRegistryEvent.getRegistry().register(
                    new BlockItem(block, new Item.Properties().group(group)).setRegistryName(block.getRegistryName()));
        }
        
        for (Class<?> item : items) {
            try {
                Constructor<?> constructor = item.getConstructor(Item.Properties.class);
                constructor.setAccessible(true);
                Object newObject = constructor.newInstance(new Item.Properties().group(group));
                if (!(newObject instanceof Item)) {
                    // the fuck you doing
                    //todo print error
                    continue;
                }
                
                Item newItem = (Item) newObject;
                RegisterItem itemAnnotation = item.getAnnotation(RegisterItem.class);
                newItem.setRegistryName(modNamespace + ":" + itemAnnotation.name());
                itemRegistryEvent.getRegistry().register(newItem);
                
                for (Field declaredField : item.getDeclaredFields()) {
                    if (declaredField.isAnnotationPresent(RegisterItem.Instance.class)) {
                        declaredField.set(null, newItem);
                    }
                }
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                e.printStackTrace();
            }
        }
    }
    
    private static synchronized void registerFluids(final RegistryEvent.Register<Fluid> fluidRegistryEvent, String modNamespace, Set<Class<?>> classes) {
        Set<Class<?>> fluids = classes.stream().filter(c -> c.isAnnotationPresent(RegisterFluid.class)).collect(Collectors.toSet());
        for (Class<?> fluid : fluids) {
            RegisterFluid annotation = fluid.getAnnotation(RegisterFluid.class);
            for (Field declaredField : fluid.getDeclaredFields()) {
                if (declaredField.isAnnotationPresent(RegisterFluid.Instance.class)) {
                    Supplier<? extends Fluid> stillSupplier = () -> {
                        try {
                            return (Fluid) declaredField.get(null);
                        } catch (IllegalAccessException e) {
                            e.printStackTrace();
                        }
                        return null;
                    };
                    Supplier<? extends Fluid> flowingSupplier = () -> {
                        Fluid stillFluid = stillSupplier.get();
                        assert stillFluid instanceof PhosphophylliteFluid;
                        return ((PhosphophylliteFluid) stillFluid).flowingVariant;
                    };
                    FluidAttributes.Builder attributes =
                            FluidAttributes.builder(new ResourceLocation(modNamespace, "fluid/" + annotation.name() + "_flowing"), new ResourceLocation(modNamespace, "fluid/" + annotation.name() + "_still"))
                                    .overlay(new ResourceLocation(modNamespace, "fluid/" + annotation.name() + "_overlay")).color(annotation.color());
                    ForgeFlowingFluid.Properties properties = new ForgeFlowingFluid.Properties(stillSupplier, flowingSupplier, attributes);
                    properties.bucket(() -> ForgeRegistries.ITEMS.getValue(new ResourceLocation(modNamespace, annotation.name() + "_bucket")));
                    properties.block(() -> (FlowingFluidBlock) ForgeRegistries.BLOCKS.getValue(new ResourceLocation(modNamespace, annotation.name() + "_block")));
                    try {
                        Constructor<?> constructor = fluid.getDeclaredConstructor(ForgeFlowingFluid.Properties.class);
                        
                        Fluid stillInstance = (Fluid) constructor.newInstance(properties);
                        Fluid flowingInstance = (Fluid) constructor.newInstance(properties);
                        
                        assert stillInstance instanceof PhosphophylliteFluid;
                        
                        ((PhosphophylliteFluid) stillInstance).isSource = true;
                        ((PhosphophylliteFluid) stillInstance).flowingVariant = (PhosphophylliteFluid) flowingInstance;
                        
                        stillInstance.setRegistryName(new ResourceLocation(modNamespace, annotation.name()));
                        flowingInstance.setRegistryName(new ResourceLocation(modNamespace, annotation.name() + "_flowing"));
                        
                        declaredField.set(null, stillInstance);
                        
                        fluidRegistryEvent.getRegistry().register(stillInstance);
                        fluidRegistryEvent.getRegistry().register(flowingInstance);
                    } catch (NoSuchMethodException | IllegalAccessException | InstantiationException | InvocationTargetException e) {
                        e.printStackTrace();
                    }
                    break;
                }
            }
        }
    }
    
    private static synchronized void registerContainers(RegistryEvent.Register<ContainerType<?>> containerTypeRegistryEvent, String modNamespace, Set<Class<?>> classes) {
        Set<Class<?>> containers = classes.stream().filter(c -> c.isAnnotationPresent(RegisterContainer.class)).collect(Collectors.toSet());
        
        for (Class<?> container : containers) {
            try {
                Constructor<?> constructor = container.getConstructor(int.class, BlockPos.class, PlayerEntity.class);
                constructor.setAccessible(true);
                
                RegisterContainer containerAnnotation = container.getAnnotation(RegisterContainer.class);
                ContainerType<?> containerType = IForgeContainerType.create(((windowId, playerInventory, data) -> {
                    try {
                        return (Container) constructor.newInstance(windowId, data.readBlockPos(), playerInventory.player);
                    } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                        throw new NullPointerException();
                    }
                }));
                
                containerType.setRegistryName(modNamespace + ":" + containerAnnotation.name());
                containerTypeRegistryEvent.getRegistry().register(containerType);
                
                for (Field declaredField : container.getDeclaredFields()) {
                    if (declaredField.isAnnotationPresent(RegisterContainer.Instance.class)) {
                        declaredField.set(null, containerType);
                    }
                }
            } catch (NullPointerException | NoSuchMethodException | IllegalAccessException e) {
                e.printStackTrace();
            }
        }
    }
    
    private static synchronized void registerTileEntities(RegistryEvent.Register<TileEntityType<?>> tileEntityTypeRegistryEvent, String modNamespace, Set<Class<?>> classes) {
        Set<Class<?>> tileEntities = classes.stream().filter(c -> c.isAnnotationPresent(RegisterTileEntity.class)).collect(Collectors.toSet());
        
        for (Class<?> tileEntity : tileEntities) {
            
            RegisterTileEntity tileEntityAnnotation = tileEntity.getAnnotation(RegisterTileEntity.class);
            
            HashSet<Block> blocks = tileEntityBlocksToRegister
                    .computeIfAbsent(tileEntity, k -> new HashSet<>());
            if (blocks.isEmpty()) {
                continue;
            }
            try {
                Constructor<?> tileConstructor = tileEntity.getConstructor();
                tileConstructor.setAccessible(true);
                Supplier<? extends TileEntity> supplier = () -> {
                    try {
                        return (TileEntity) tileConstructor.newInstance();
                    } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                        e.printStackTrace();
                    }
                    return null;
                };
                @SuppressWarnings("rawtypes") Constructor<TileEntityType.Builder> constructor = TileEntityType.Builder.class
                        .getDeclaredConstructor(Supplier.class, Set.class);
                constructor.setAccessible(true);
                @SuppressWarnings({"unchecked",
                        "ConstantConditions"}) TileEntityType<?> tileEntityType = constructor
                        .newInstance(supplier, ImmutableSet.copyOf(blocks)).build(null);
                tileEntityType.setRegistryName(modNamespace + ":" + tileEntityAnnotation.name());
                tileEntityTypeRegistryEvent.getRegistry().register(tileEntityType);
                for (Field field : tileEntity.getFields()) {
                    if (field.isAnnotationPresent(RegisterTileEntity.Type.class)) {
                        field.set(null, tileEntityType);
                    }
                }
            } catch (NoSuchMethodException | IllegalAccessException | InstantiationException | InvocationTargetException e) {
                e.printStackTrace();
            }
            
        }
    }
    
    private static synchronized void onClientSetup(FMLClientSetupEvent clientSetupEvent, String modNamespace, Set<Class<?>> classes) {
        HashSet<Block> blocksRegistered = Registry.blocksRegistered.get(modNamespace);
        for (Block block : blocksRegistered) {
            if (!block.getDefaultState().isSolid()) {
                for (Method declaredMethod : block.getClass().getDeclaredMethods()) {
                    if (declaredMethod.isAnnotationPresent(RegisterBlock.RenderLayer.class)) {
                        try {
                            declaredMethod.setAccessible(true);
                            Object returned = declaredMethod.invoke(block);
                            if (returned instanceof RenderType) {
                                RenderTypeLookup.setRenderLayer(block, (RenderType) returned);
                            }
                        } catch (IllegalAccessException | InvocationTargetException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
    }
    
    private static final HashMap<Block, IBakedModel> bakedModelsToRegister;
    
    static {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            bakedModelsToRegister = new HashMap<>();
        } else {
            bakedModelsToRegister = null;
        }
    }
    
    @OnlyIn(Dist.CLIENT)
    public static synchronized void registerBakedModel(Block block, IBakedModel model) {
        bakedModelsToRegister.put(block, model);
    }
    
    @OnlyIn(Dist.CLIENT)
    private static synchronized void onModelBake(ModelBakeEvent event, String modNamespace, Set<Class<?>> classes) {
        HashSet<Block> blocksRegistered = Registry.blocksRegistered.get(modNamespace);
        if (blocksRegistered == null) {
            return;
        }
        for (Block block : blocksRegistered) {
            IBakedModel model = bakedModelsToRegister.get(block);
            if (model != null) {
                event.getModelRegistry().put(new ModelResourceLocation(Objects.requireNonNull(block.getRegistryName()), ""), model);
            }
        }
    }
    
    private static synchronized void onTextureStitch(TextureStitchEvent.Pre event, String modNamespace, Set<Class<?>> classes) {
        if (!event.getMap().getTextureLocation().equals(AtlasTexture.LOCATION_BLOCKS_TEXTURE)) {
            return;
        }
        HashSet<Block> blocksRegistered = Registry.blocksRegistered.get(modNamespace);
        if (blocksRegistered == null) {
            return;
        }
    }
    
    private static void onLoadComplete(final FMLLoadCompleteEvent e, String modNamespace, Set<Class<?>> classes) {
        ConfigManager.modLoadingFinished = true;
        ConfigManager.runPostLoads();
    }
    
    private static synchronized void registerWorldGen(String modNamespace, Set<Class<?>> classes, BiomeLoadingEvent biomeEvent) {
        Set<Class<?>> ores = classes.stream().filter(c -> c.isAnnotationPresent(RegisterOre.class)).collect(Collectors.toSet());
        HashSet<Block> blocksRegistered = Registry.blocksRegistered
                .computeIfAbsent(modNamespace, k -> new HashSet<>());
        
        for (Class<?> ore : ores) {
            try {
                RegisterBlock blockAnnotation = ore.getAnnotation(RegisterBlock.class);
                
                Block oreInstance = null;
                for (Block block : blocksRegistered) {
                    if (Objects.requireNonNull(block.getRegistryName())
                            .toString().equals(modNamespace + ":" + blockAnnotation.name())) {
                        oreInstance = block;
                    }
                }
                
                assert oreInstance instanceof IPhosphophylliteOre;
                IPhosphophylliteOre oreInfo = (IPhosphophylliteOre) oreInstance;
                
                if (oreInfo.spawnBiomes().length > 0) {
                    if (!Arrays.asList(oreInfo.spawnBiomes()).contains(
                            Objects.requireNonNull(biomeEvent.getName()).toString())) {
                        continue;
                    }
                }
                
                final Block finalOreInstance = oreInstance;
                
                if ((biomeEvent.getCategory() == Biome.Category.NETHER) != oreInfo.isNetherOre()) {
                    return;
                }
                
                biomeEvent.getGeneration().getFeatures(GenerationStage.Decoration.UNDERGROUND_ORES).add(() -> {
                    
                    RuleTest fillerBlock = oreInfo.isNetherOre() ? OreFeatureConfig.FillerBlockType.field_241884_c : OreFeatureConfig.FillerBlockType.field_241882_a;
                    
                    return Feature.ORE
                            .withConfiguration(new OreFeatureConfig(fillerBlock, finalOreInstance.getDefaultState(), oreInfo.size()))
                            .withPlacement(Placement.field_242907_l.configure(new TopSolidRangeConfig(oreInfo.minLevel(), 0, oreInfo.maxLevel())))
                            .func_242728_a()
                            .func_242731_b(oreInfo.count());
                });
            } catch (NullPointerException e) {
                e.printStackTrace();
            }
        }
    }
    
    public static synchronized void registerConfigs(String modNamespace, Set<Class<?>> classes) {
        Set<Class<?>> configs = classes.stream().filter(c -> c.isAnnotationPresent(RegisterConfig.class)).collect(Collectors.toSet());
        
        for (Class<?> config : configs) {
            ConfigManager.registerConfig(config, modNamespace);
        }
    }
}
