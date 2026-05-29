<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import * as THREE from 'three';
import { VRMLoaderPlugin, VRMUtils, VRMHumanBoneName, VRMExpressionPresetName } from '@pixiv/three-vrm';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';

export type DhState = 'idle' | 'listening' | 'thinking' | 'speaking' | 'error';

const props = defineProps<{
  state: DhState;
  jawAmplitude?: number;
}>();

const container = ref<HTMLElement | null>(null);
const loading = ref(true);
const loadError = ref<string | null>(null);

const NATURAL = {
  lArm:[0.06,0.61,1.36], rArm:[0.06,0.11,-1.39],
  lFore:[0,0,0], rFore:[0,0,0],
  lHand:[-0.54,-0.19,0.31], rHand:[0.31,-0.24,-0.24],
};
const R1={rArm:[-0.62,-0.51,-1.28],rFore:[0.10,1.15,-0.04],rHand:[0.81,-0.01,-0.36]};
const R2={rArm:[-0.13,0.33,-1.28],rFore:[0.57,0.50,0.26],rHand:[1.11,0.00,-0.81]};
const L1={lArm:[1.10,0.05,1.01],lFore:[0.59,-0.58,1.13],lHand:[-0.08,0.03,0.10]};
const L2={lArm:[0.72,0.50,1.27],lFore:[1.01,-0.06,0.33],lHand:[-0.54,-0.19,0.31]};
interface GD {lArm:number[];lFore:number[];lHand:number[];rArm:number[];rFore:number[];rHand:number[]}
function mkG(l:any,r:any):GD{return{
  lArm:l.lArm||[...NATURAL.lArm],lFore:l.lFore||[0,0,0],lHand:l.lHand||[...NATURAL.lHand],
  rArm:r.rArm||[...NATURAL.rArm],rFore:r.rFore||[0,0,0],rHand:r.rHand||[...NATURAL.rHand],
}}
const G_LIST:GD[]=[mkG({},{}),mkG({},{...R1}),mkG({},{...R2}),mkG({...L1},{}),mkG({...L2},{})];
function la(a:number[],b:number[],t:number){return[a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t,a[2]+(b[2]-a[2])*t]}

let gIdx=0,gPh:'idle'|'in'|'hold'|'out'='idle',gPt=0,gPd=0,gNt=0,gE=0,gSrc=mkG({},{});

const dShow=ref(false);
const dLAX=ref(NATURAL.lArm[0]),dLAY=ref(NATURAL.lArm[1]),dLAZ=ref(NATURAL.lArm[2]);
const dRAX=ref(NATURAL.rArm[0]),dRAY=ref(NATURAL.rArm[1]),dRAZ=ref(NATURAL.rArm[2]);
const dLFX=ref(0),dLFY=ref(0),dLFZ=ref(0),dRFX=ref(0),dRFY=ref(0),dRFZ=ref(0);
const dLHX=ref(NATURAL.lHand[0]),dLHY=ref(NATURAL.lHand[1]),dLHZ=ref(NATURAL.lHand[2]);
const dRHX=ref(NATURAL.rHand[0]),dRHY=ref(NATURAL.rHand[1]),dRHZ=ref(NATURAL.rHand[2]);

let scene:THREE.Scene,camera:THREE.PerspectiveCamera,renderer:THREE.WebGLRenderer;
let af=0,clk:THREE.Clock,vrm:any=null,vg:THREE.Group|null=null;

function initScene(){
  if(!container.value)return;
  const w=container.value.clientWidth,h=container.value.clientHeight;
  renderer=new THREE.WebGLRenderer({antialias:true,alpha:true,preserveDrawingBuffer:true});
  renderer.setSize(w,h);renderer.setPixelRatio(Math.min(window.devicePixelRatio,2));
  renderer.setClearColor(0,0);renderer.shadowMap.enabled=true;renderer.shadowMap.type=THREE.PCFSoftShadowMap;
  renderer.toneMapping=THREE.ACESFilmicToneMapping;renderer.toneMappingExposure=1.0;
  container.value.appendChild(renderer.domElement);
  scene=new THREE.Scene();
  camera=new THREE.PerspectiveCamera(40,w/h,0.1,100);camera.position.set(0,1.05,3.2);camera.lookAt(0,0.65,0);
  scene.add(new THREE.AmbientLight(0x8899aa,0.9));
  const k=new THREE.DirectionalLight(0xfff5ee,2.8);k.position.set(0.4,2,4);k.castShadow=true;
  k.shadow.mapSize.width=1024;k.shadow.mapSize.height=1024;k.shadow.camera.near=0.5;k.shadow.camera.far=20;
  k.shadow.bias=-0.0003;k.shadow.normalBias=0.02;scene.add(k);
  const f=new THREE.DirectionalLight(0xaaccdd,1.5);f.position.set(-0.8,0.6,2.5);scene.add(f);
  const r=new THREE.DirectionalLight(0xffffff,1.6);r.position.set(0,2.2,-2);scene.add(r);
  const p=new THREE.Mesh(new THREE.PlaneGeometry(8,8),new THREE.ShadowMaterial({opacity:0.12}));
  p.rotation.x=-Math.PI/2;p.position.y=-1.2;p.receiveShadow=true;scene.add(p);
  clk=new THREE.Clock();
}

async function loadModel(){
  loading.value=true;loadError.value=null;
  if(vrm){if(vg){scene.remove(vg);vg=null;}
    vrm.scene.traverse((o:THREE.Object3D)=>{if(o instanceof THREE.Mesh){o.geometry?.dispose();(o.material as any)?.dispose?.()}});vrm=null;}
  try{
    const l=new GLTFLoader();l.register((p:any)=>new VRMLoaderPlugin(p,{autoUpdateHumanBones:false}));
    const g=await l.loadAsync('/keito.vrm');vrm=g.userData.vrm;
    if(!vrm)throw new Error('VRM not found');
    VRMUtils.removeUnnecessaryVertices(g.scene);VRMUtils.combineSkeletons(g.scene);VRMUtils.combineMorphs(vrm);
    vg=new THREE.Group();vg.add(vrm.scene);vg.position.set(0,-0.3,0);vg.rotation.y=Math.PI;scene.add(vg);
    loading.value=false;
  }catch(e){console.error(e);loadError.value='load failed';loading.value=false;}
}

function animate(){
  af=requestAnimationFrame(animate);
  const dt=Math.min(clk.getDelta(),0.1);
  if(!loading.value&&vrm){
    const el=clk.elapsedTime,st=props.state,amp=props.jawAmplitude??0;
    const sp=st==='speaking'||amp>0.01,s=Math.sin,sc=vrm.scene;
    const lA=sc.getObjectByName('J_Bip_L_UpperArm'),rA=sc.getObjectByName('J_Bip_R_UpperArm');
    const lF=sc.getObjectByName('J_Bip_L_LowerArm'),rF=sc.getObjectByName('J_Bip_R_LowerArm');
    const lH=sc.getObjectByName('J_Bip_L_Hand'),rH=sc.getObjectByName('J_Bip_R_Hand');

    if(sp){switch(gPh){
      case'idle':if(el>=gNt){const n=Math.floor(Math.random()*G_LIST.length);if(n!==gIdx){
        gSrc.lArm=lA?[lA.rotation.x,lA.rotation.y,lA.rotation.z]:gSrc.lArm;
        gSrc.rArm=rA?[rA.rotation.x,rA.rotation.y,rA.rotation.z]:gSrc.rArm;
        gSrc.lFore=lF?[lF.rotation.x,lF.rotation.y,lF.rotation.z]:gSrc.lFore;
        gSrc.rFore=rF?[rF.rotation.x,rF.rotation.y,rF.rotation.z]:gSrc.rFore;
        gSrc.lHand=lH?[lH.rotation.x,lH.rotation.y,lH.rotation.z]:gSrc.lHand;
        gSrc.rHand=rH?[rH.rotation.x,rH.rotation.y,rH.rotation.z]:gSrc.rHand;
        gIdx=n;gPh='in';gPt=el;gPd=0.5;}}break;
      case'in':{const t=Math.min((el-gPt)/gPd,1);gE=t<0.5?2*t*t:-1+(4-2*t)*t;if(t>=1){gPh='hold';gPt=el;gPd=2+Math.random()*3;}break;}
      case'hold':gE=1;if(el-gPt>=gPd){gPh='out';gPt=el;gPd=0.6+Math.random()*0.4;}break;
      case'out':{const t=Math.min((el-gPt)/gPd,1);gE=1-(t<0.5?2*t*t:-1+(4-2*t)*t);if(t>=1){gPh='idle';gNt=el+1.5+Math.random()*4;}break;}
    }}else{gIdx=0;gE=0;gPh='idle';gNt=el+2;}

    const tg=G_LIST[gIdx];
    if(lA)lA.rotation.set(...la(gSrc.lArm,tg.lArm,gE));
    if(rA)rA.rotation.set(...la(gSrc.rArm,tg.rArm,gE));
    if(lF)lF.rotation.set(...la(gSrc.lFore,tg.lFore,gE));
    if(rF)rF.rotation.set(...la(gSrc.rFore,tg.rFore,gE));
    if(lH)lH.rotation.set(...la(gSrc.lHand,tg.lHand,gE));
    if(rH)rH.rotation.set(...la(gSrc.rHand,tg.rHand,gE));

    vrm.update(dt);
    if(vg){vg.rotation.z=s(el*0.3)*0.04;vg.rotation.y=Math.PI+s(el*0.2+1)*0.06;}
    const hb=vrm.humanoid.getNormalizedBoneNode(VRMHumanBoneName.Head);
    if(hb){if(sp){hb.rotation.z=s(el*2.5)*0.06+s(el*1.7)*0.04;hb.rotation.x=s(el*2.3)*0.05;hb.rotation.y=s(el*0.6)*0.12;}
      else{hb.rotation.z=s(el*0.5)*0.025;hb.rotation.x=s(el*0.35)*0.018;hb.rotation.y=s(el*0.12)*0.08;}}
    const sb=vrm.humanoid.getNormalizedBoneNode(VRMHumanBoneName.Spine);
    if(sb)sb.scale.setScalar(1+s(el*1.2)*0.012);
    const ex=vrm.expressionManager;
    if(sp&&ex){ex.setValue(VRMExpressionPresetName.Aa,0.03+amp*0.35);ex.setValue(VRMExpressionPresetName.Happy,amp*0.15);}
    else if(ex){ex.setValue(VRMExpressionPresetName.Aa,0);ex.setValue(VRMExpressionPresetName.Happy,0);}
  }
  renderer.render(scene,camera);
}

function onResize(){
  if(!container.value||!camera||!renderer)return;
  const w=container.value.clientWidth,h=container.value.clientHeight;
  camera.aspect=w/h;camera.updateProjectionMatrix();renderer.setSize(w,h);
}

onMounted(()=>{initScene();loadModel();animate();window.addEventListener('resize',onResize);});
onUnmounted(()=>{cancelAnimationFrame(af);window.removeEventListener('resize',onResize);
  if(vrm){if(vg)scene.remove(vg);vrm.scene.traverse((o:THREE.Object3D)=>{if(o instanceof THREE.Mesh){o.geometry?.dispose();(o.material as any)?.dispose?.()}});}
  renderer?.dispose();renderer?.domElement.remove();});
</script>

<template>
  <div ref="container" class="dh-container">
    <div v-if="loading" class="dh-status">loading...</div>
    <div v-if="loadError" class="dh-status dh-error">{{ loadError }}</div>
    <div v-if="dShow&&!loading" class="debug-panel" @pointerdown.stop @pointermove.stop>
      <div class="debug-title">Tuner<button class="hide-btn" @click="dShow=false">X</button></div>
      <div class="debug-section">L UpperArm</div>
      <div class="debug-row">X<input type="range" min="-3.14" max="3.14" step="0.01" v-model="dLAX"/><code>{{Number(dLAX).toFixed(2)}}</code></div>
      <div class="debug-row">Y<input type="range" min="-3.14" max="3.14" step="0.01" v-model="dLAY"/><code>{{Number(dLAY).toFixed(2)}}</code></div>
      <div class="debug-row">Z<input type="range" min="-3.14" max="3.14" step="0.01" v-model="dLAZ"/><code>{{Number(dLAZ).toFixed(2)}}</code></div>
      <div class="debug-section">R UpperArm</div>
      <div class="debug-row">X<input type="range" min="-3.14" max="3.14" step="0.01" v-model="dRAX"/><code>{{Number(dRAX).toFixed(2)}}</code></div>
      <div class="debug-row">Y<input type="range" min="-3.14" max="3.14" step="0.01" v-model="dRAY"/><code>{{Number(dRAY).toFixed(2)}}</code></div>
      <div class="debug-row">Z<input type="range" min="-3.14" max="3.14" step="0.01" v-model="dRAZ"/><code>{{Number(dRAZ).toFixed(2)}}</code></div>
      <div class="debug-section">L Forearm</div>
      <div class="debug-row">X<input type="range" min="-3.14" max="3.14" step="0.01" v-model="dLFX"/><code>{{Number(dLFX).toFixed(2)}}</code></div>
      <div class="debug-row">Y<input type="range" min="-3.14" max="3.14" step="0.01" v-model="dLFY"/><code>{{Number(dLFY).toFixed(2)}}</code></div>
      <div class="debug-row">Z<input type="range" min="-3.14" max="3.14" step="0.01" v-model="dLFZ"/><code>{{Number(dLFZ).toFixed(2)}}</code></div>
      <div class="debug-section">R Forearm</div>
      <div class="debug-row">X<input type="range" min="-3.14" max="3.14" step="0.01" v-model="dRFX"/><code>{{Number(dRFX).toFixed(2)}}</code></div>
      <div class="debug-row">Y<input type="range" min="-3.14" max="3.14" step="0.01" v-model="dRFY"/><code>{{Number(dRFY).toFixed(2)}}</code></div>
      <div class="debug-row">Z<input type="range" min="-3.14" max="3.14" step="0.01" v-model="dRFZ"/><code>{{Number(dRFZ).toFixed(2)}}</code></div>
      <div class="debug-section">L Hand</div>
      <div class="debug-row">X<input type="range" min="-3.14" max="3.14" step="0.01" v-model="dLHX"/><code>{{Number(dLHX).toFixed(2)}}</code></div>
      <div class="debug-row">Y<input type="range" min="-3.14" max="3.14" step="0.01" v-model="dLHY"/><code>{{Number(dLHY).toFixed(2)}}</code></div>
      <div class="debug-row">Z<input type="range" min="-3.14" max="3.14" step="0.01" v-model="dLHZ"/><code>{{Number(dLHZ).toFixed(2)}}</code></div>
      <div class="debug-section">R Hand</div>
      <div class="debug-row">X<input type="range" min="-3.14" max="3.14" step="0.01" v-model="dRHX"/><code>{{Number(dRHX).toFixed(2)}}</code></div>
      <div class="debug-row">Y<input type="range" min="-3.14" max="3.14" step="0.01" v-model="dRHY"/><code>{{Number(dRHY).toFixed(2)}}</code></div>
      <div class="debug-row">Z<input type="range" min="-3.14" max="3.14" step="0.01" v-model="dRHZ"/><code>{{Number(dRHZ).toFixed(2)}}</code></div>
    </div>
  </div>
</template>

<style scoped>
.dh-container{width:100%;height:100%;position:relative;overflow:hidden;border-radius:12px;background:radial-gradient(ellipse at center,#1a2030 0%,#0d1117 100%)}
.dh-status{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);color:#71767b;font-size:14px;pointer-events:none}
.dh-error{color:#f4212e}
.debug-panel{position:absolute;top:8px;left:8px;background:rgba(0,0,0,0.92);padding:8px 10px;border-radius:8px;z-index:999;font-size:10px;color:#0f0;min-width:240px;pointer-events:auto;user-select:none;max-height:95%;overflow-y:auto}
.debug-title{font-size:12px;color:#fff;margin-bottom:4px;display:flex;justify-content:space-between}
.hide-btn{background:#c00;color:#fff;border:none;border-radius:3px;cursor:pointer;padding:0 6px}
.debug-section{color:#ff0;font-size:9px;margin:4px 0 1px;border-top:1px solid #333;padding-top:2px}
.debug-row{display:flex;align-items:center;gap:3px;margin:1px 0}
.debug-row input[type=range]{flex:1;height:10px;pointer-events:auto;cursor:pointer}
.debug-row code{min-width:36px;text-align:right;color:#ff0;font-size:10px;background:#222;padding:1px 3px;border-radius:2px}
</style>
