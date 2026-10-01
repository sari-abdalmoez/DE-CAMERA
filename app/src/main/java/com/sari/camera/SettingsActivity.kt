package com.sari.camera

import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity

class SettingsActivity:ComponentActivity(){
 override fun onCreate(b:Bundle?){super.onCreate(b);val p=getSharedPreferences("settings",0);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)}
  list.addView(TextView(this).apply{text="SARI Camera Settings";textSize=24f});
  fun sw(label:String,key:String,default:Boolean){list.addView(Switch(this).apply{text=label;isChecked=p.getBoolean(key,default);setOnCheckedChangeListener{_,v->p.edit().putBoolean(key,v).apply()}})}
  sw("AI enhancement","ai",true);sw("Save RAW with RAW mode","raw",true);sw("Video stabilization","stabilization",true);sw("Thermal adaptation","thermal",true)
  list.addView(TextView(this).apply{text="Performance profile is selected automatically from memory and thermal state."});setContentView(ScrollView(this).apply{addView(list)})
 }
}
