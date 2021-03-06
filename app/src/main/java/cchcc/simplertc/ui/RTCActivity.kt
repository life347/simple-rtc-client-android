package cchcc.simplertc.ui

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.support.v7.app.AlertDialog
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.*
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.EditText
import cchcc.simplertc.R
import cchcc.simplertc.ext.inputMethodManager
import cchcc.simplertc.ext.simpleAlert
import cchcc.simplertc.ext.startAnimationTada
import cchcc.simplertc.ext.toast
import cchcc.simplertc.model.ChatMessage
import cchcc.simplertc.viewmodel.RTCViewModel
import com.github.salomonbrys.kodein.*
import com.github.salomonbrys.kodein.android.appKodein
import kotlinx.android.synthetic.main.act_rtc.*
import kotlinx.android.synthetic.main.li_chat_message.view.*
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class RTCActivity : BaseActivity(), KodeinInjected {
    override val injector: KodeinInjector = KodeinInjector()

    private val viewModel: RTCViewModel by instance()
    private val chatListAdapter: ChatListAdapter by lazy { ChatListAdapter(this) }
    private var passedTimeSubscript: Subscription? = null
    private val fadeOutHudLayoutAnimation: Animation by lazy {
        AnimationUtils.loadAnimation(this, R.anim.fadeout).apply {
            fillAfter = true
            startOffset = 3000
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationRepeat(animation: Animation?) {
                }

                override fun onAnimationEnd(animation: Animation) = rl_video.bringToFront()

                override fun onAnimationStart(animation: Animation?) {
                }
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        injector.inject(appKodein().instance<Kodein>(RTCActivity::class))

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        setContentView(R.layout.act_rtc)

        with(viewModel) {
            eventObservable.subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(event@{ occurredViewModelEvent(it) }
                            , error@{ simpleAlert(it.toString()) }
                            , terminated@{ terminatedRTC() })
                    .addToComposite()

            val kodein = Kodein {
                bind<Context>() with singleton { this@RTCActivity }
                bind<GLSurfaceView>() with singleton { glv_video }
            }
            onCreate(kodein)
        }

        val roomName = intent.getStringExtra("roomName")
        tv_title.text = roomName
        ll_hud_bottom.visibility = View.INVISIBLE
        tv_sending_message.setOnClickListener { clickedSendingMessage() }
        bt_terminate.setOnClickListener { clickedTerminate() }
        rl_video.setOnClickListener { showHudLayoutForaWhile() }
        iv_received_message.setOnClickListener { showHudLayoutForaWhile() }
        with(rv_chat) {
            layoutManager = LinearLayoutManager(this@RTCActivity)
            adapter = chatListAdapter
        }

        showHudLayoutForaWhile()
    }

    private fun showHudLayoutForaWhile() {
        iv_received_message.visibility = View.INVISIBLE
        rl_hud.bringToFront()
        rl_hud.alpha = 1.0f
        rl_hud.startAnimation(fadeOutHudLayoutAnimation)
    }

    private fun occurredViewModelEvent(event: RTCViewModel.Event) {
        when (event) {
            is RTCViewModel.Event.Connected -> {
                tv_waiting.visibility = View.GONE
                ll_hud_bottom.visibility = View.VISIBLE
                addMessageToListView(ChatMessage(Date(), "system", "Start"))

                passedTimeSubscript = Observable.interval(1, TimeUnit.SECONDS).startWith(0)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe { tv_title.text = "${String.format("%02d", it / 60)}:${String.format("%02d", it % 60)}" }
                        .apply { addToComposite() }
            }
            is RTCViewModel.Event.Chat -> {
                val mostFrontView = root_rl_rtc.getChildAt(root_rl_rtc.childCount - 1)
                if (mostFrontView != rl_hud && event.message.sender == "opp") {
                    iv_received_message.visibility = View.VISIBLE
                    iv_received_message.startAnimationTada()
                }
                addMessageToListView(event.message)
            }
        }
    }

    private fun addMessageToListView(chatMessage: ChatMessage) {
        chatListAdapter.addAndNotify(chatMessage)
        rv_chat.scrollToPosition(chatListAdapter.list.size - 1)
    }

    private fun terminatedRTC() {
        tv_sending_message.setOnClickListener(null)
        tv_sending_message.text = getString(R.string.terminated)
        passedTimeSubscript?.unsubscribe()
        addMessageToListView(ChatMessage(Date(), "system", getString(R.string.terminated)))
        toast(R.string.terminated)
    }

    private fun clickedTerminate() {
        viewModel.terminate()
        finish()
    }

    private fun clickedSendingMessage() {
        val et_message = EditText(this)
        AlertDialog.Builder(this)
                .setTitle(R.string.sending_message)
                .setView(et_message)
                .setPositiveButton(R.string.send) { dlg, w ->
                    viewModel.sendChatMessage(et_message.text.toString())
                }.show()
        Handler().postDelayed({ inputMethodManager.showSoftInput(et_message, 0) }, 400)
    }

    override fun onResume() {
        super.onResume()
        glv_video.onResume()
    }

    override fun onPause() {
        glv_video.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        viewModel.terminate()
        viewModel.onDestroy()
        super.onDestroy()
    }

    override fun onBackPressed() {
        AlertDialog.Builder(this)
                .setMessage(R.string.are_you_sure_to_quit)
                .setPositiveButton(R.string.yes) { dlg, w -> super.onBackPressed() }
                .setNegativeButton(R.string.no) { dlg, w -> }
                .show()
    }

    class ChatListAdapter(val context: Context) : RecyclerView.Adapter<ChatListAdapter.ViewHolder>() {

        val list = mutableListOf<ChatMessage>()
        private val chatDateFormat: SimpleDateFormat by lazy {
            SimpleDateFormat("HH:mm", Locale.getDefault())
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view)

        fun addAndNotify(chatMessage: ChatMessage) {
            list.add(chatMessage)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup?, viewType: Int): ViewHolder =
                ViewHolder(LayoutInflater.from(context).inflate(R.layout.li_chat_message, parent, false))

        override fun getItemCount(): Int = list.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) = with(holder.itemView) {
            val chatMessage = list[position]
            tv_date.text = chatDateFormat.format(chatMessage.dateTime)
            tv_sender.text = chatMessage.sender
            tv_message.text = chatMessage.message
        }
    }
}
