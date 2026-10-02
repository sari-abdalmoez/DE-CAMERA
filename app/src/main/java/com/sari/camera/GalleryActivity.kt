package com.sari.camera

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity

class GalleryActivity: ComponentActivity(){
 override fun onCreate(b:Bundle?){super.onCreate(b);val scroll=ScrollView(this);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};scroll.addView(list);setContentView(scroll)
  val projection=arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.DISPLAY_NAME,MediaStore.Images.Media.MIME_TYPE)
  val (selection,args)=if(android.os.Build.VERSION.SDK_INT >= 29){
   "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to arrayOf("Pictures/SARI Camera/%")
  } else {
   "${MediaStore.Images.Media.DATA} LIKE ?" to arrayOf("%/Pictures/SARI Camera/%")
  }
  contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,projection,selection,args,"${MediaStore.Images.Media.DATE_ADDED} DESC")?.use{c->while(c.moveToNext()){val id=c.getLong(0);val name=c.getString(1);val mime=c.getString(2);val uri=Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,id.toString());val item=Button(this).apply{text=name;setOnClickListener{startActivity(Intent(this@GalleryActivity,ViewerActivity::class.java).apply{data=uri;putExtra("mime",mime);putExtra("name",name)})}};list.addView(item,LinearLayout.LayoutParams(-1,64))}}
 }
}
